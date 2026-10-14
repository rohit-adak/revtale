package io.github.revtale

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * A place or route the rider saved for one-tap navigation ("Home", "Office", "Home → Gym").
 * [from] is set for a saved route (where it usually starts); navigation always goes to [lat]/[lon].
 */
data class SavedPlace(val name: String, val lat: Double, val lon: Double, val from: String? = null) {
    val label get() = from?.let { "$it → $name" } ?: name
}

/** Saved places in prefs as JSON. Encoding is pure (unit-tested). */
object Places {
    fun encode(list: List<SavedPlace>): String = JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon).apply { p.from?.let { put("from", it) } }) }
    }.toString()

    fun decode(s: String?): List<SavedPlace> = runCatching {
        val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            SavedPlace(it.getString("name"), it.getDouble("lat"), it.getDouble("lon"), it.optString("from").takeIf { f -> f.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    /** Adds or replaces (same label) and keeps the list short enough for the Live tab. */
    fun with(list: List<SavedPlace>, p: SavedPlace): List<SavedPlace> =
        (list.filter { it.label != p.label } + p).takeLast(12)

    fun load(ctx: Context) = decode(BleService.prefs(ctx).getString("places", null))
    fun save(ctx: Context, list: List<SavedPlace>) = BleService.prefs(ctx).edit().putString("places", encode(list)).apply()

    /**
     * Starts Google Maps turn-by-turn to the place (two-wheeler mode where Maps has it) and remembers it as the
     * destination, so the cluster can show the weather there before the first turn.
     */
    fun navigate(ctx: Context, p: SavedPlace, usualMin: Int? = null) {
        BleService.prefs(ctx).edit().putString("navDest", "${p.lat},${p.lon},${System.currentTimeMillis()},${usualMin ?: -1}")
            .putString("navDestName", p.name).apply()
        val uri = Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=l")
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The destination picked in the app in the last 15 minutes (lat, lon), if any. */
    fun recentDestination(ctx: Context, now: Long = System.currentTimeMillis()): Pair<Double, Double>? =
        BleService.prefs(ctx).getString("navDest", null)?.split(',')?.takeIf { it.size >= 3 }?.let { p ->
            val at = p[2].toLongOrNull() ?: 0
            if (now - at > 15 * 60_000L) null else p[0].toDoubleOrNull()?.let { la -> p[1].toDoubleOrNull()?.let { la to it } }
        }

    /** Your usual minutes for the trip picked in the app (stored with it), if known. */
    fun recentUsualMinutes(ctx: Context): Int? =
        BleService.prefs(ctx).getString("navDest", null)?.split(',')?.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }

    /** Google Maps' route overview (all routes, live traffic colours) without starting navigation. */
    fun preview(ctx: Context, p: SavedPlace) {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${p.lat},${p.lon}&travelmode=two-wheeler")
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }.onFailure { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Walking directions to [lat]/[lon] (find the parked bike). */
    fun walkTo(ctx: Context, lat: Double, lon: Double) {
        val uri = Uri.parse("google.navigation:q=$lat,$lon&mode=w")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(My bike)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
