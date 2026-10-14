package io.github.revtale

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.text.format.DateUtils
import android.widget.RemoteViews

/** What the widget says right now. Pure (unit-tested); [BikeWidget] reads the inputs and draws it. */
data class WidgetContent(val status: String, val dot: Int, val headline: String, val detail: String,
    /** A few characters for the 2×1 size: "4:29 PM", "23.4 km", "Parked". */
    val short: String = headline)

object WidgetText {
    enum class State { RIDING, CONNECTED, WAITING, SLEEPING, OFF }

    const val RED = 0xFFE5202E.toInt()
    private const val GREEN = 0xFF34C759.toInt()
    private const val AMBER = 0xFFF5B83D.toInt()
    private const val GREY = 0xFF8E8E96.toInt()

    /**
     * The one thing worth knowing now, in this order: the route you're on, the ride in progress, a ride without your
     * phone, where the bike is parked, else ready.
     */
    fun of(
        state: State, nowMs: Long,
        rideKm: Double? = null, speed: Int? = null, rideMin: Int? = null,
        navMinutes: Int? = null, navLeftM: Int? = null, navDest: String? = null,
        away: AwayTrip? = null, parked: ParkedSpot? = null
    ): WidgetContent {
        val (status, dot) = when (state) {
            State.RIDING -> "Riding" to RED
            State.CONNECTED -> "Connected" to GREEN
            State.WAITING -> "Waiting" to AMBER
            State.SLEEPING -> "Asleep" to GREY
            State.OFF -> "Off" to GREY
        }
        val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        fun ago(ms: Long): String {
            val m = ((nowMs - ms) / 60_000).coerceAtLeast(0)
            return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
        }
        return when {
            navMinutes != null && state != State.OFF -> time.format(java.util.Date(nowMs + navMinutes * 60_000L)).let { at ->
                WidgetContent(status, dot, "Arrive $at",
                    listOfNotNull("$navMinutes min", navLeftM?.let { "${decimal(it / 1000.0)} km left" }, navDest?.let { "to $it" }).joinToString(" · "), at)
            }
            state == State.RIDING && rideKm != null -> WidgetContent(status, dot, "${decimal(rideKm)} km this ride",
                listOfNotNull(speed?.let { "$it km/h" }, rideMin?.let { "$it min" }).joinToString(" · "), "${decimal(rideKm)} km")
            away != null -> WidgetContent(status, dot, "Ridden ${decimal(away.km)} km",
                "Without you" + if (away.fuelFrom >= 0 && away.fuelTo >= 0) " · fuel ${away.fuelFrom} → ${away.fuelTo} bars" else "",
                "${decimal(away.km)} km")
            parked != null -> WidgetContent(status, dot, "Parked" + (parked.place?.let { " · $it" } ?: ""),
                listOfNotNull(ago(parked.atMs), parked.note.takeIf { it.isNotBlank() }).joinToString(" · "), "Parked")
            else -> WidgetContent(status, dot, "Ready to ride", if (state == State.CONNECTED) "Bike is on" else "Turn the ignition on", "Ready")
        }
    }

    /** Fuel as bars for the widget: "■■■■□□". */
    fun fuelBars(bars: Int, max: Int = Calibration.DEFAULT_FUEL_MAX): String =
        if (bars < 0) "—" else "■".repeat(bars.coerceIn(0, max)) + "□".repeat(max - bars.coerceIn(0, max))
}

/**
 * Home-screen widget, resizable to any size: 1×1 is the mark with a status dot; 2×1 the state and one line; a one-row
 * strip adds Ride to and Find bike; 2×2 and taller columns stack them (tall adds fuel, odometer, this month); 4×2 has
 * two places and Find bike; 4×3 and up adds the stats row and a third place.
 */
class BikeWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    companion object {
        private var lastLive = 0L

        /** At most once a minute while riding (each update redraws the widget on the home screen). */
        fun refreshWhileRiding(ctx: Context) {
            val now = System.currentTimeMillis()
            if (now - lastLive < 60_000) return
            lastLive = now; refresh(ctx)
        }

        /** Redraw every placed widget and the quick-settings tile (cheap; called on connect / disconnect / park). */
        fun refresh(ctx: Context) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, StatusTile::class.java)) }
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BikeWidget::class.java))
            if (ids.isEmpty()) return
            val views = runCatching { views(ctx) }.getOrNull() ?: return
            mgr.updateAppWidget(ids, views)
        }

        private fun views(ctx: Context): RemoteViews {
            val p = BleService.prefs(ctx)
            val s = BleService.instance
            val ride = s?.liveRide?.takeIf { s.gattState == "connected" }
            val state = when {
                ride != null && ride.speed > 0 || (ride?.distKm ?: 0.0) > 0.1 -> WidgetText.State.RIDING
                s?.gattState == "connected" -> WidgetText.State.CONNECTED
                s?.sessionOn == true -> WidgetText.State.WAITING
                BikePresence.on(ctx) -> WidgetText.State.SLEEPING
                else -> WidgetText.State.OFF
            }
            val nav = s?.lastNavInfo
            val c = WidgetText.of(state, System.currentTimeMillis(),
                ride?.distKm, ride?.speed, ride?.let { ((it.lastMs - it.startMs) / 60_000).toInt() },
                nav?.minutesLeft, nav?.distLeftM, nav?.let { p.getString("navDestName", null) },
                Away.trips(ctx).lastOrNull { !it.seen }, Parking.load(ctx))
            val places = Places.load(ctx)
            val name = p.getString("bikeName", null) ?: "RevTale"
            val parked = Parking.load(ctx)
            val live = s?.takeIf { it.gattState == "connected" }?.let { LiveTelemetry.decode(it.lastDecoded.toMap()) }
            val fuel = live?.fuel ?: p.getInt("awayFuel", -1)
            val odo = live?.odometer ?: p.getString("awayOdo", null)?.toDoubleOrNull()
            val monthKey = java.text.SimpleDateFormat("yyyyMM", java.util.Locale.US).format(java.util.Date())
            val monthKm = if (p.getString("monthKey", null) == monthKey) p.getFloat("monthKm", 0f).toDouble() else 0.0

            fun build(layout: Int) = RemoteViews(ctx.packageName, layout).apply {
                setTextViewText(R.id.widget_name, name)
                setTextViewText(R.id.widget_status, c.status)
                setInt(R.id.widget_dot, "setColorFilter", c.dot)
                setTextViewText(R.id.widget_headline, c.headline)
                setTextViewText(R.id.widget_detail, c.detail)
                // small: the status line also names the bike
                if (layout == R.layout.widget_small) {
                    setTextViewText(R.id.widget_headline, c.short)
                    setTextViewText(R.id.widget_status, c.detail.ifBlank { c.status })
                }
                if (layout == R.layout.widget_strip) setTextViewText(R.id.widget_status, "${c.status} · ${c.detail.ifBlank { name }}")
                // the tap goes where the widget's headline points: the ride, the bike away, the parked spot, the last ride
                setOnClickPendingIntent(R.id.widget_root, Open.pending(ctx, when (state) {
                    WidgetText.State.RIDING, WidgetText.State.CONNECTED -> Open.LIVE
                    else -> if (Away.trips(ctx).any { !it.seen } || parked != null) Open.DRAWER else Open.LAST_RIDE
                }))
                val goIds = listOf(R.id.widget_go1, R.id.widget_go2, R.id.widget_go3)
                goIds.forEachIndexed { i, id ->
                    val place = places.getOrNull(i)
                    when {
                        place != null -> {
                            setViewVisibility(id, android.view.View.VISIBLE)
                            setTextViewText(id, place.name)
                            setOnClickPendingIntent(id, GoActivity.pending(ctx, GoActivity.GO_PLACE, 40 + i, i))
                        }
                        i == 0 -> { // no places yet: one button that opens the app to add them
                            setViewVisibility(id, android.view.View.VISIBLE)
                            setTextViewText(id, "+ Add place")
                            setOnClickPendingIntent(id, Open.pending(ctx, Open.DRAWER))
                        }
                        else -> setViewVisibility(id, android.view.View.GONE)
                    }
                }
                setTextViewText(R.id.widget_find, if (layout == R.layout.widget_strip) "Find" else "Find bike")
                setOnClickPendingIntent(R.id.widget_find, if (parked != null) GoActivity.pending(ctx, GoActivity.FIND_BIKE, 21) else Open.pending(ctx, Open.DRAWER))
                setTextViewText(R.id.widget_stat1, WidgetText.fuelBars(fuel, Calibration.fuelMax(ctx, fuel))); setTextViewText(R.id.widget_stat1_label, "Fuel")
                setTextViewText(R.id.widget_stat2, odo?.let { "%,d".format(it.toInt()) } ?: "—"); setTextViewText(R.id.widget_stat2_label, "Odometer")
                setTextViewText(R.id.widget_stat3, "${decimal(monthKm, 0)} km"); setTextViewText(R.id.widget_stat3_label, "This month")
                // big widget: the other facts the headline isn't already showing
                val infos = listOfNotNull(
                    parked?.takeIf { !c.headline.startsWith("Parked") }?.let { "Parked" + (it.place?.let { pl -> " at $pl" } ?: "") +
                        (it.note.takeIf { n -> n.isNotBlank() }?.let { n -> " · $n" } ?: "") },
                    p.getString("lastRide", null)?.split(',')?.takeIf { it.size >= 4 }?.let { r ->
                        "Last ride: ${r[0]} km · ${r[1]} min · top ${r[2]} km/h · " +
                            java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(r[3].toLongOrNull() ?: 0))
                    }).ifEmpty { listOf("Your last ride, parking spot and fuel show here after a ride.") }
                listOf(R.id.widget_info1, R.id.widget_info2).forEachIndexed { i, id ->
                    infos.getOrNull(i)?.let { setViewVisibility(id, android.view.View.VISIBLE); setTextViewText(id, it) }
                        ?: setViewVisibility(id, android.view.View.GONE)
                }
                if (layout == R.layout.widget_tall) {
                    setTextViewText(R.id.widget_stat2_label, "Odo"); setTextViewText(R.id.widget_stat3_label, "Month")
                }
            }
            // the launcher picks the biggest layout that fits whatever size the rider drags the widget to
            return RemoteViews(mapOf(
                android.util.SizeF(40f, 40f) to build(R.layout.widget_tiny),
                android.util.SizeF(110f, 40f) to build(R.layout.widget_small),
                android.util.SizeF(250f, 40f) to build(R.layout.widget_strip),
                android.util.SizeF(110f, 110f) to build(R.layout.widget_square),
                android.util.SizeF(110f, 270f) to build(R.layout.widget_tall),
                android.util.SizeF(180f, 110f) to build(R.layout.widget_medium),
                android.util.SizeF(250f, 200f) to build(R.layout.widget_large)))
        }

        /** Status line for the quick-settings tile. */
        fun statusText(ctx: Context): String {
            val s = BleService.instance
            val name = BleService.prefs(ctx).getString("bikeName", null) ?: "Your bike"
            return name + " · " + when {
                s?.gattState == "connected" -> s.liveRide?.let { "riding · ${decimal(it.distKm)} km" } ?: "connected"
                s?.sessionOn == true -> "waiting"
                else -> "off"
            }
        }
    }
}

/**
 * An invisible screen that turns a widget / tile / shortcut tap into the action (Android doesn't let a broadcast
 * open Google Maps from the background, but an activity may).
 */
class GoActivity : Activity() {
    companion object {
        const val GO_FIRST_PLACE = "go_first"
        const val GO_PLACE = "go_place"
        const val FIND_BIKE = "find_bike"
        const val OPEN_APP = "open_app"

        fun pending(ctx: Context, action: String, code: Int, index: Int = 0): PendingIntent = PendingIntent.getActivity(ctx, code,
            Intent(ctx, GoActivity::class.java).setAction(action).putExtra("index", index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            when (intent?.action) {
                GO_FIRST_PLACE, GO_PLACE -> Places.load(this).getOrNull(intent.getIntExtra("index", 0))?.let { Places.navigate(this, it) } ?: openApp()
                FIND_BIKE -> Parking.load(this)?.let { Places.walkTo(this, it.lat, it.lon) } ?: openApp()
                else -> openApp()
            }
        }
        finish()
    }

    private fun openApp() = startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Quick-settings tile: shows whether the bike is connected; tap opens the app. */
class StatusTile : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        val s = BleService.instance
        t.state = if (s?.gattState == "connected") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "RevTale"
        t.subtitle = when {
            s?.gattState == "connected" -> "Connected"
            s?.sessionOn == true -> "Waiting"
            else -> "Off"
        }
        t.updateTile()
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 34)
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
