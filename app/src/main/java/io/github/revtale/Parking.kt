package io.github.revtale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Where the bike was switched off: GPS spot, street name, and the rider's own note / photo ("B2, pillar 14"). */
data class ParkedSpot(val lat: Double, val lon: Double, val atMs: Long, val place: String?, val note: String, val photo: String?)

object Parking {
    const val NOTIF = 46
    const val CHANNEL = "revtale_parked"

    fun load(ctx: Context): ParkedSpot? {
        val p = BleService.prefs(ctx)
        val lat = p.getString("parkedLat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("parkedLon", null)?.toDoubleOrNull() ?: return null
        return ParkedSpot(lat, lon, p.getLong("parkedAt", 0),
            p.getString("parkedPlace", null), p.getString("parkedNote", "") ?: "", p.getString("parkedPhoto", null))
    }

    /** A new spot replaces the old one; the note and photo belong to the old spot, so they go. */
    fun save(ctx: Context, lat: Double, lon: Double, atMs: Long) {
        val old = BleService.prefs(ctx).getString("parkedPhoto", null)
        old?.let { runCatching { java.io.File(it).delete() } }
        BleService.prefs(ctx).edit().putString("parkedLat", lat.toString())
            .putString("parkedLon", lon.toString()).putLong("parkedAt", atMs)
            .remove("parkedPlace").putString("parkedNote", "").remove("parkedPhoto").apply()
    }

    fun setPlace(ctx: Context, place: String?) = BleService.prefs(ctx).edit().putString("parkedPlace", place).apply()
    fun setNote(ctx: Context, note: String) = BleService.prefs(ctx).edit().putString("parkedNote", note.take(120)).apply()
    fun setPhoto(ctx: Context, path: String?) = BleService.prefs(ctx).edit().putString("parkedPhoto", path).apply()

    /** Street name from Android's geocoder (blocking, off the main thread); null offline. */
    @Suppress("DEPRECATION")
    fun streetName(ctx: Context, lat: Double, lon: Double): String? = runCatching {
        android.location.Geocoder(ctx).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.thoroughfare, a.subLocality ?: a.locality).distinct().joinToString(", ").ifBlank { null }
        }
    }.getOrNull()

    /** "Parked at MG Road" with Walk there / Add a note. Quiet (no sound): you just got off the bike. */
    fun notify(ctx: Context, spot: ParkedSpot) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Where you parked", NotificationManager.IMPORTANCE_LOW))
        val open = Open.pending(ctx, Open.DRAWER)
        val walk = PendingIntent.getActivity(ctx, 11, Intent(Intent.ACTION_VIEW,
            android.net.Uri.parse("google.navigation:q=${spot.lat},${spot.lon}&mode=w")), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(NOTIF, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Parked" + (spot.place?.let { " at $it" } ?: ""))
            .setContentText("Add a note or photo (floor, pillar) so you find it later.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Add note / photo", open).build())
            .addAction(Notification.Action.Builder(null, "Walk there", walk).build())
            .setAutoCancel(true)
            .build())
    }
}
