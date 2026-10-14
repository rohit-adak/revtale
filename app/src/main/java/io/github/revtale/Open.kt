package io.github.revtale

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Where a notification, widget or tile tap lands in the app (the "open" extra MainActivity hands to [LinkApp]). */
object Open {
    const val EXTRA = "open"
    const val LIVE = "live"
    const val RIDES = "rides"
    /** The newest ride's details (the one just ridden). */
    const val LAST_RIDE = "lastRide"
    /** Rides → How you ride (last parked spot, usual routes). */
    const val OVERVIEW = "overview"
    const val SETTINGS = "settings"
    const val CALIBRATE = "calibrate"
    /** Live with its drawer pulled up: the parked spot, rides while you were away, places. */
    const val DRAWER = "drawer"

    fun intent(ctx: Context, to: String): Intent = Intent(ctx, MainActivity::class.java)
        .putExtra(EXTRA, to).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** One request code per destination, so each tap keeps its own extra. */
    fun pending(ctx: Context, to: String): PendingIntent = PendingIntent.getActivity(ctx, 900 + to.hashCode() % 90,
        intent(ctx, to).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
