package io.github.revtale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bike was ridden without this phone (a friend borrowed it): what the bike itself remembers, read the next time
 * it connects — odometer, fuel gauge and the dash's top-speed reading — plus where it stood before and where it is now.
 * The route itself can't be known: the bike has no GPS, only the phone does.
 */
data class AwayTrip(
    val fromMs: Long, val toMs: Long, val km: Double,
    val fuelFrom: Int, val fuelTo: Int,
    /** The dash's top-speed reading before / after (5A10 b10); only meaningful when it went up. */
    val topFrom: Int, val topTo: Int,
    val leftFrom: String?, val backAt: String?,
    val seen: Boolean = false
) {
    val topSpeedRose get() = topTo > topFrom && topFrom >= 0

    fun json(): JSONObject = JSONObject().put("from", fromMs).put("to", toMs).put("km", km).put("f0", fuelFrom).put("f1", fuelTo)
        .put("t0", topFrom).put("t1", topTo).put("left", leftFrom ?: "").put("back", backAt ?: "").put("seen", seen)

    companion object {
        fun of(j: JSONObject) = AwayTrip(j.getLong("from"), j.getLong("to"), j.getDouble("km"), j.optInt("f0", -1), j.optInt("f1", -1),
            j.optInt("t0", -1), j.optInt("t1", -1), j.optString("left").ifBlank { null }, j.optString("back").ifBlank { null }, j.optBoolean("seen"))
    }
}

/** What the bike looked like when this phone last saw it. */
data class BikeSnapshot(val atMs: Long, val odoKm: Double, val fuelBars: Int, val top: Int)

object Away {
    /** At least this much on the odometer counts as a ride (the reading has 100 m steps and can jitter by one). */
    const val MIN_KM = 0.5

    /** A ride without this phone between [before] and [now]; null when the bike didn't move. Pure, unit-tested. */
    fun detect(before: BikeSnapshot?, now: BikeSnapshot): AwayTrip? {
        if (before == null || before.odoKm <= 0 || now.odoKm <= 0) return null
        val km = now.odoKm - before.odoKm
        if (km < MIN_KM || km > 5000) return null // 5000+: a different bike or a bad reading
        return AwayTrip(before.atMs, now.atMs, Math.round(km * 10) / 10.0, before.fuelBars, now.fuelBars, before.top, now.top, null, null)
    }

    fun encode(list: List<AwayTrip>): String = JSONArray().apply { list.forEach { put(it.json()) } }.toString()
    fun decode(s: String?): List<AwayTrip> = runCatching {
        val a = JSONArray(s ?: return emptyList()); (0 until a.length()).map { AwayTrip.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun trips(ctx: Context) = decode(BleService.prefs(ctx).getString("awayTrips", null))

    fun add(ctx: Context, t: AwayTrip) =
        BleService.prefs(ctx).edit().putString("awayTrips", encode((trips(ctx) + t).takeLast(20))).apply()

    fun markSeen(ctx: Context) =
        BleService.prefs(ctx).edit().putString("awayTrips", encode(trips(ctx).map { it.copy(seen = true) })).apply()

    fun lastSeen(ctx: Context): BikeSnapshot? = BleService.prefs(ctx).let { p ->
        val odo = p.getString("awayOdo", null)?.toDoubleOrNull() ?: return null
        BikeSnapshot(p.getLong("awayAt", 0), odo, p.getInt("awayFuel", -1), p.getInt("awayTop", -1))
    }

    fun remember(ctx: Context, s: BikeSnapshot) = BleService.prefs(ctx).edit().putString("awayOdo", s.odoKm.toString())
        .putLong("awayAt", s.atMs).putInt("awayFuel", s.fuelBars).putInt("awayTop", s.top).apply()

    fun notify(ctx: Context, t: AwayTrip) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("revtale_away", "Rides without your phone", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(47, Notification.Builder(ctx, "revtale_away")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Your bike was ridden ${decimal(t.km)} km while you were away")
            .setContentText(listOfNotNull(
                if (t.fuelFrom >= 0 && t.fuelTo >= 0) "Fuel ${t.fuelFrom} → ${t.fuelTo} bars" else null,
                t.backAt?.let { "now at $it" }).joinToString(" · ").ifBlank { "Tap for details." })
            .setAutoCancel(true)
            .setContentIntent(Open.pending(ctx, Open.DRAWER))
            .build())
    }
}
