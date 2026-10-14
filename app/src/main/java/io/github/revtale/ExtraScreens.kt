@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.util.Locale

// ---- Live tab: ride to a saved place, where you parked --------------------------------------------------------

/** Cards on the Live tab while parked. */
fun LazyListScope.liveExtras(ui: LinkUiController) {
    if (ui.awayTrips.any { !it.seen }) item { AwayCard(ui) }
    item { GoCard(ui) }
    item { ParkedCard(ui) }
}

@Composable
private fun GoCard(ui: LinkUiController) {
    var saving by remember { mutableStateOf(false) }
    var going by remember { mutableStateOf<SavedPlace?>(null) }
    var editing by remember { mutableStateOf(false) }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Ride to")
            Icon(Icons.Outlined.Navigation, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (ui.places.isEmpty()) Muted("Save Home, Office or any spot once; then one tap shows how long it takes and starts Google Maps directions.")
        ui.places.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { p ->
                    Box(Modifier.weight(1f)) {
                        if (editing) OutlinedButton(onClick = hapticAction({ ui.removePlace(p) }), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(p.label, maxLines = 1)
                        } else Button(onClick = hapticAction({ going = p }), modifier = Modifier.fillMaxWidth()) { Text(p.label, maxLines = 1) }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ saving = true })) { Icon(Icons.Outlined.AddLocationAlt, null); Spacer(Modifier.width(6.dp)); Text("Add a place") }
            if (ui.places.isNotEmpty()) TextButton(onClick = hapticAction({ editing = !editing })) { Text(if (editing) "Done" else "Edit") }
        }
        Muted("Search, pick on the map, or share a place from Google Maps to RevTale. Usual routes: Rides → How you ride.")
    }
    if (saving) PlacePickerDialog(ui, null) { saving = false }
    going?.let { p -> GoDialog(ui, p) { going = null } }
}

/** Before Maps starts: how far, how long (your usual time, empty roads), when you'd arrive; weather last. */
@Composable
private fun GoDialog(ui: LinkUiController, p: SavedPlace, onClose: () -> Unit) {
    var route by remember { mutableStateOf<RoutePreview?>(null) }
    var routeDone by remember { mutableStateOf(false) }
    var weather by remember { mutableStateOf<WeatherNow?>(null) }
    LaunchedEffect(p) { route = ui.routePreview(p); routeDone = true }
    LaunchedEffect(p) { weather = ui.weatherAt(p.lat, p.lon).getOrNull() }
    AlertDialog(onDismissRequest = onClose, icon = { Icon(Icons.Outlined.Navigation, null) }, title = { Text("Ride to ${p.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = route
                if (!routeDone) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Muted("Working out the route…")
                } else if (r == null || r.km == null && r.usualMin == null) Muted("Couldn't work out the route here (no location or offline). Google Maps will.")
                else {
                    val mins = r.usualMin ?: r.freeFlowMin
                    Text(listOfNotNull(r.km?.let { "${decimal(it)} km" }, mins?.let { "about $it min" }).joinToString(" · "),
                        style = MaterialTheme.typography.headlineSmall)
                    mins?.let { m ->
                        val at = java.time.LocalTime.now().plusMinutes(m.toLong())
                        Text("Arrive around " + at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (r.usualMin != null) Muted("Your usual time: ${r.usualMin} min (${r.usualRides} rides)" +
                        (r.freeFlowMin?.let { " · empty roads ~$it min" } ?: ""))
                    else r.freeFlowMin?.let { Muted("On empty roads ~$it min. Ride it a couple of times and your own usual time shows here.") }
                }
                Muted("Live traffic: tap Traffic for Google's routes. After Start, Google's ETA goes on your cluster.")
                weather?.let { w ->
                    HorizontalDivider()
                    Muted("Weather there: ${w.phoneText()}" + if (w.raining) " — ride in Rain mode" else "")
                }
            }
        },
        confirmButton = { TextButton(onClick = hapticAction({ ui.goTo(p, route?.usualMin); onClose() })) { Text("Start") } },
        dismissButton = {
            Row {
                TextButton(onClick = hapticAction(onClose)) { Text("Cancel") }
                TextButton(onClick = hapticAction({ ui.previewRoute(p); onClose() })) { Text("Traffic") }
            }
        })
}

/** "Your bike was ridden 23 km while you were away": what the bike itself remembered (odometer, fuel, top speed). */
@Composable
private fun AwayCard(ui: LinkUiController) {
    val fmt = remember { java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()) }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("While you were away")
            Icon(Icons.Outlined.TwoWheeler, null, tint = MaterialTheme.colorScheme.primary)
        }
        ui.awayTrips.filter { !it.seen }.reversed().forEach { t ->
            Text("Ridden ${decimal(t.km)} km", style = MaterialTheme.typography.headlineMedium)
            Muted("Between ${fmt.format(java.util.Date(t.fromMs))} and ${fmt.format(java.util.Date(t.toMs))}")
            if (t.fuelFrom >= 0 && t.fuelTo >= 0) Text(if (t.fuelTo > t.fuelFrom) "Fuel ${t.fuelFrom} → ${t.fuelTo} bars (someone filled up)"
                else "Fuel ${t.fuelFrom} → ${t.fuelTo} bars")
            if (t.topSpeedRose) Text("The dash's top-speed reading went from ${t.topFrom} to ${t.topTo} km/h")
            Muted(listOfNotNull(t.leftFrom?.let { "Left from $it" }, t.backAt?.let { "back at $it" }).joinToString(" · ").ifBlank { "" })
        }
        Muted("The bike remembers distance, fuel and top speed, not the route; only a phone or GPS tracker on the bike can record where it went.")
        TextButton(onClick = hapticAction(ui::markAwaySeen), contentPadding = PaddingValues(0.dp)) { Text("Got it") }
    }
}

@Composable
private fun ParkedCard(ui: LinkUiController) {
    val spot = ui.parkedSpot
    var note by remember(spot?.atMs) { mutableStateOf(spot?.note ?: "") }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow(if (spot == null) "Where you parked" else "Your bike is parked")
            Icon(Icons.Outlined.LocalParking, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (spot == null) {
            Muted("After each ride RevTale remembers where you switched off. Or save the spot now.")
            OutlinedButton(onClick = hapticAction(ui::parkHere)) { Text("I parked here") }
            return@RaceCard
        }
        Text(spot.place ?: "%.5f, %.5f".format(Locale.US, spot.lat, spot.lon), style = MaterialTheme.typography.titleLarge)
        Muted(DateUtils.getRelativeTimeSpanString(spot.atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
        spot.photo?.let { path ->
            val bmp = remember(path) {
                runCatching { android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
            }
            bmp?.let { Image(it.asImageBitmap(), "Parking photo", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
        }
        OutlinedTextField(note, { note = it.take(120); ui.setParkingNote(note) }, singleLine = true,
            label = { Text("Note (floor, pillar, lane)") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = hapticAction({ Places.walkTo(ui.context, spot.lat, spot.lon) })) { Icon(Icons.Outlined.DirectionsWalk, null); Spacer(Modifier.width(6.dp)); Text("Walk there") }
            OutlinedButton(onClick = hapticAction(ui::takeParkingPhoto)) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
        }
        TextButton(onClick = hapticAction(ui::parkHere), contentPadding = PaddingValues(0.dp)) { Text("Moved it? I parked here") }
    }
}

// ---- Rides tab: this month, ride score, sharing ---------------------------------------------------------------

@Composable
fun MonthCard(rides: List<RideMonthEntry>, fills: List<FillUp>) {
    val r = remember(rides, fills) { MonthlyReports.build(rides, fills) }
    val monthName = remember(r.month) { java.text.DateFormatSymbols(Locale.getDefault()).months[r.month] }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("$monthName so far")
            Icon(Icons.Outlined.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (r.rides == 0) { Muted("No rides yet this month."); return@RaceCard }
        Text("${decimal(r.km)} km", style = MaterialTheme.typography.headlineLarge)
        r.prevKm?.takeIf { it > 0 }?.let { prev ->
            val change = (r.km - prev) * 100 / prev
            Muted("${if (change >= 0) "+" else ""}${decimal(change, 0)}% vs last month (${decimal(prev)} km)")
        }
        StatGrid(listOfNotNull("Rides" to "${r.rides}", "Riding time" to duration(r.movingS),
            "Longest ride" to "${decimal(r.longestKm)} km", "Top speed" to "${r.topSpeed} km/h",
            r.avgScore?.let { "Avg ride score" to "$it/100" }, r.fuelSpend.takeIf { it > 0 }?.let { "Fuel spent" to "₹${decimal(it, 0)}" }))
        r.busiestDay?.let { Muted("You ride most on ${it}s.") }
    }
}

@Composable
fun ScoreCard(score: RideScore) {
    RaceCard {
        Eyebrow("Ride score")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${score.score}", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            Text("/100", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp))
            Spacer(Modifier.weight(1f))
            Text(score.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        }
        LinearProgressIndicator(progress = { score.score / 100f }, modifier = Modifier.fillMaxWidth())
        score.minus.forEach { (what, pts) -> Row(Modifier.fillMaxWidth()) { Muted(what, Modifier.weight(1f)); Text("−$pts") } }
        score.tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        if (score.minus.isEmpty()) Muted("No hard brakes, no hard launches, no time at the redline. Keep it up.")
    }
}

@Composable
fun ShareRideRow(ui: LinkUiController, file: java.io.File) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = hapticAction({ ui.shareRide(file, asImage = true) }), modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text("Share picture")
        }
        OutlinedButton(onClick = hapticAction({ ui.shareRide(file, asImage = false) }), modifier = Modifier.weight(1f)) { Text("Export GPX") }
    }
}

@Composable
fun TankHistory(fills: List<FillUp>) {
    val tanks = remember(fills) { FuelStats.tanks(fills).reversed() }
    if (tanks.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = hapticAction({ open = !open }), contentPadding = PaddingValues(0.dp)) { Text(if (open) "Hide tank history" else "Mileage per tank (${tanks.size})") }
    if (open) {
        val best = tanks.maxOf { it.kmPerL }
        tanks.take(12).forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Muted(java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(t.fill.tMs)) + " · ${decimal(t.km, 0)} km", Modifier.weight(1f))
                    Text("${decimal(t.kmPerL)} km/l · ₹${decimal(t.costPerKm, 2)}/km", style = MaterialTheme.typography.titleSmall)
                }
                LinearProgressIndicator(progress = { (t.kmPerL / best).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---- Settings ---------------------------------------------------------------------------------------------------

@Composable
fun BikesCard(ui: LinkUiController, onAddBike: () -> Unit) {
    val state = ui.state
    var forget by remember { mutableStateOf<SavedBike?>(null) }
    var renaming by remember { mutableStateOf<SavedBike?>(null) }
    RaceCard {
        Eyebrow(if (ui.bikes.size > 1) "Your bikes" else "Your bike")
        if (ui.bikes.isEmpty()) SectionTitle("No bike selected")
        ui.bikes.forEach { b ->
            val active = b.addr == state.bikeAddress
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (active) Icons.Outlined.TwoWheeler else Icons.Outlined.Circle, null,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(if (active) 24.dp else 12.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium)
                    Muted(if (active) "In use" + if (state.connected) " · connected" else "" else b.addr)
                }
                IconButton(onClick = hapticAction({ renaming = b })) { Icon(Icons.Outlined.Edit, "Rename ${b.name}") }
                if (!active) TextButton(onClick = hapticAction({ ui.useBike(b) }), enabled = !ui.busy) { Text("Use") }
                IconButton(onClick = hapticAction({ forget = b })) { Icon(Icons.Outlined.DeleteOutline, "Forget ${b.name}") }
            }
        }
        OutlinedButton(onClick = hapticAction(onAddBike)) { Text(if (ui.bikes.isEmpty()) "Find my bike" else "Add another bike") }
        SettingSwitch("Auto-connect", "When the ignition turns on.", state.autoConnect) { ui.setPreference("autoConnect", it) }
    }
    renaming?.let { b ->
        var name by remember { mutableStateOf(b.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Name your bike") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text("Bike name") }, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Apache RTR 160 4V", "Apache RTR 200 4V").forEach { n -> FilterChip(name == n, hapticSelection({ name = n }), label = { Text(n.removePrefix("Apache ")) }) }
                    }
                    Muted("Shown in the app, the widget, notifications and your ride pictures.")
                }
            },
            confirmButton = { TextButton(onClick = hapticAction({ ui.renameBike(b, name); renaming = null }), enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = hapticAction({ renaming = null })) { Text("Cancel") } })
    }
    forget?.let { b ->
        AlertDialog(onDismissRequest = { forget = null }, title = { Text("Forget ${b.name}?") },
            text = { Text("RevTale will stop connecting to it. Your saved rides stay on this phone.") },
            confirmButton = { TextButton(onClick = hapticAction({ ui.removeBike(b); forget = null })) { Text("Forget bike") } },
            dismissButton = { TextButton(onClick = hapticAction({ forget = null })) { Text("Cancel") } })
    }
}

@Composable
fun BackgroundCard(ui: LinkUiController) {
    RaceCard {
        Eyebrow("In the background")
        SettingSwitch("No notification while waiting", "Android watches for your bike and wakes RevTale when it turns on. " +
            "The notification only shows while connected. If connecting gets slow on your phone, turn this off.", ui.presenceOn) { ui.setPresence(it) }
        OutlinedButton(onClick = hapticAction(ui::batteryHelp), modifier = Modifier.fillMaxWidth()) { Text("Keep it connected in the background") }
        OutlinedButton(onClick = hapticAction({
            val mgr = android.appwidget.AppWidgetManager.getInstance(ui.context)
            if (mgr.isRequestPinAppWidgetSupported) mgr.requestPinAppWidget(android.content.ComponentName(ui.context, BikeWidget::class.java), null, null)
            else ui.notice = "Long-press your home screen → Widgets → RevTale."
        }), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Widgets, null); Spacer(Modifier.width(8.dp)); Text("Add the widget to your home screen") }
        Muted("Resize it: small shows the state, wide adds Ride-to and Find bike, tall adds fuel, odometer and this month. " +
            "Quick tile: edit your quick settings and drag in RevTale.")
    }
}

@Composable
fun BackupCard(ui: LinkUiController) {
    val last = ui.lastBackup
    RaceCard {
        Eyebrow("Backup")
        SectionTitle("Keep your rides safe", "Rides, fuel log, saved places and settings in one file. Save it to Drive or Downloads; " +
            "restore it after a new phone or a reinstall.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = hapticAction(ui::exportBackup), enabled = !ui.busy) { Text("Back up now") }
            OutlinedButton(onClick = hapticAction(ui::importBackup), enabled = !ui.busy) { Text("Restore") }
        }
        Muted(if (last == 0L) "No backup yet." else "Last backup " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }
}

@Composable
fun ReportCard(ui: LinkUiController) {
    var open by remember { mutableStateOf(false) }
    val crashed = remember { BugReport.hasCrash(ui.context) }
    OutlinedCard(onClick = hapticAction({ open = true }), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.BugReport, null)
            Column(Modifier.weight(1f)) {
                Text("Report a problem", style = MaterialTheme.typography.titleMedium)
                Muted(if (crashed) "RevTale crashed last time. Sending the report helps fix it." else "Sends what happened and the app's details to whoever you pick")
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
    if (open) {
        var what by remember { mutableStateOf("") }
        var days by remember { mutableIntStateOf(3) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("Report a problem") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(what, { what = it.take(500) }, label = { Text("What happened?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Text("Add logs from", style = MaterialTheme.typography.titleSmall)
                    // every connection, button press, call and cluster message from those days: pick the day it happened
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "None", 1 to "Today", 3 to "3 days", 7 to "7 days").forEach { (d, label) ->
                            FilterChip(days == d, onClick = hapticSelection({ days = d }), label = { Text(label) })
                        }
                    }
                    Muted("Logs help a lot. They can include your routes: send them only to someone you trust.")
                }
            },
            confirmButton = { TextButton(onClick = hapticAction({ ui.sendReport(what, days); open = false })) { Text("Send") } },
            dismissButton = { TextButton(onClick = hapticAction({ open = false })) { Text("Cancel") } })
    }
}

/** Weather Union key: the weather feature switches on once a key is saved. */
@Composable
fun WeatherKeyField(ui: LinkUiController) {
    var key by remember { mutableStateOf(ui.weatherKey) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(key, { key = it.trim(); ui.saveWeatherKey(key) }, singleLine = true, label = { Text("Weather Union API key") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            supportingText = { Text("Free at weatherunion.com → Free API. Stays on this phone.") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = hapticAction({
            runCatching { ui.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.weatherunion.com/dashboard/"))) }
        }), contentPadding = PaddingValues(0.dp)) { Text("Get a key") }
    }
}

// ---- First run: what it does, your bike, what each permission is for -------------------------------------------

@Composable
fun OnboardingScreen(ui: LinkUiController) {
    var step by rememberSaveableInt()
    val steps = 5
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        Box(Modifier.weight(1f)) {
            when (step) {
                0 -> OnboardingPage {
                    Icon(Icons.Outlined.SportsMotorsports, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    PageHeading("Welcome", "Your bike, connected", "Turn-by-turn arrows, who's calling, texts and your ride stats on your " +
                        "Apache's cluster, plus a ride journal on your phone.")
                    Muted("Independent app, not made by or affiliated with any bike maker. Your rides stay on this phone.")
                }
                1 -> SetupScreen(ui, onDone = { step = 2 })
                2 -> OnboardingPermissions(ui)
                4 -> OnboardingPage {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    PageHeading("One more thing", "Calibrate your bike", "Every model reports its switches and draws its dash a " +
                        "little differently. Two minutes, parked: press each switch, shift through the gears, tell RevTale what your dash shows.")
                    Muted("Do it once now, or later from Settings → Calibrate your bike. Until then a reminder floats on the Live screen.")
                    Button(onClick = hapticAction({ ui.finishOnboarding(); ui.openRequest = Open.CALIBRATE }),
                        enabled = ui.state.bikeAddress != null, modifier = Modifier.fillMaxWidth()) { Text("Calibrate now") }
                    if (ui.state.bikeAddress == null) Muted("Pick your bike first (step 2), then calibrate.")
                }
                else -> OnboardingPage {
                    PageHeading("Last bits", "Rider preferences", "All optional; change them any time in Cluster and Settings.")
                    var name by remember { mutableStateOf(ui.prefs.getString("name", "RIDER") ?: "RIDER") }
                    OutlinedTextField(name, { name = it.take(17) }, singleLine = true, label = { Text("Your name on the cluster") },
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = hapticAction({ ui.saveName(name) })) { Text("Save name") }
                    Text("Weather before each route", style = MaterialTheme.typography.titleMedium)
                    WeatherKeyField(ui)
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = hapticAction(ui::finishOnboarding)) { Text("Skip") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = hapticAction({ step-- })) { Text("Back") }
                Button(onClick = hapticAction({ if (step < steps - 1) step++ else ui.finishOnboarding() })) {
                    Text(when { step == steps - 1 -> "Start riding"; step == 1 && ui.state.bikeAddress == null -> "Later"; else -> "Next" })
                }
            }
        }
    }
}

@Composable
private fun rememberSaveableInt(): MutableState<Int> = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }

@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun OnboardingPermissions(ui: LinkUiController) {
    val ctx = ui.context
    ui.state // re-read what Android allows on every refresh (the rider comes back from Settings)
    val notifAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val overlay = Settings.canDrawOverlays(ctx)
    val calls = ui.callPermissionsMissing().isEmpty()
    val battery = ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    OnboardingPage {
        PageHeading("Permissions", "Choose your permissions", "Allow the ones you want. Each says what it's for.")
        PermissionRow(Icons.Outlined.TurnRight, "Directions on the cluster", "Notification access: reads Google Maps' turn notification.", notAccessFix(ui), notifAccess)
        PermissionRow(Icons.Outlined.Call, "Calls and texts", "Phone, contacts and SMS: the caller's name on the dash, decline with i, text back.",
            ui::askCallPermissions, calls)
        PermissionRow(Icons.Outlined.Mic, "Gemini on the i switch", "Display over other apps: lets the switch open your assistant.",
            { ui.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }, overlay)
        PermissionRow(Icons.Outlined.BatteryChargingFull, "Stay connected", "Unrestricted battery: Android won't cut the link mid-ride.", ui::batteryHelp, battery)
    }
}

private fun notAccessFix(ui: LinkUiController): () -> Unit = { ui.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }

@Composable
private fun PermissionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, why: String, onAllow: () -> Unit, granted: Boolean) {
    RaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Muted(why) }
            if (granted) Icon(Icons.Outlined.CheckCircle, "Allowed", tint = MaterialTheme.colorScheme.primary)
            else TextButton(onClick = hapticAction(onAllow)) { Text("Allow") }
        }
    }
}

// ---- engine heat --------------------------------------------------------------------------------------------------

/** One ride: how hot the engine got, how warm it was outside, and each hot stretch with when, where and why. */
@Composable
fun HeatCard(heat: RideHeat, rideStartMs: Long) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Engine heat")
            Icon(Icons.Outlined.Thermostat, null, tint = MaterialTheme.colorScheme.primary)
        }
        Text("${heat.maxC} °C peak", style = MaterialTheme.typography.headlineMedium)
        Muted("Average ${heat.avgMovingC} °C while moving" + (heat.ambientC?.let { " · about $it °C outside" } ?: "") +
            " · peak ${duration((heat.maxAtMs - rideStartMs) / 1000.0)} into the ride")
        if (heat.events.isEmpty()) Muted("Never reached ${Heat.HOT} °C. Normal for this engine.")
        else {
            heat.verdict?.let { Text("Mostly: ${it.who}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            Muted("${decimal(heat.hotMinutes, 0)} min at ${Heat.HOT} °C or more")
            heat.events.forEach { e -> HeatEventRow(e, null, ctx) }
        }
    }
}

@Composable
private fun HeatEventRow(e: HeatEvent, place: String?, ctx: android.content.Context) {
    val time = remember(e.startMs) { java.text.SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(java.util.Date(e.startMs)) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HorizontalDivider()
        Text("${e.peakC} °C · ${e.cause.who}", style = MaterialTheme.typography.titleSmall)
        Muted("$time · ${decimal(e.minutes, 1)} min" + (place?.let { " · $it" } ?: ""))
        Muted(e.evidence)
        if (!e.lat.isNaN()) TextButton(onClick = hapticAction({
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:${e.lat},${e.lon}?q=${e.lat},${e.lon}(Engine hot)"))) }
        }), contentPadding = PaddingValues(0.dp)) { Text("Where it happened") }
    }
}

/** All rides: your usual peak, the hottest ride, and what keeps making it hot. */
@Composable
fun HeatOverviewCard(heats: List<Pair<Long, RideHeat>>, names: Map<Pair<Double, Double>, String>) {
    if (heats.isEmpty()) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val peaks = heats.map { it.second.maxC }.sorted()
    val hottest = heats.maxBy { it.second.maxC }
    val events = heats.flatMap { it.second.events }.sortedByDescending { it.startMs }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Engine heat")
            Icon(Icons.Outlined.Thermostat, null, tint = MaterialTheme.colorScheme.primary)
        }
        StatGrid(listOf("Your usual peak" to "${peaks[peaks.size / 2]} °C", "Hottest ride" to "${hottest.second.maxC} °C",
            "Hot stretches" to "${events.size}", "Rides measured" to "${heats.size}"))
        if (events.isEmpty()) Muted("No ride reached ${Heat.HOT} °C. Your engine is running cool.")
        else {
            val byCause = events.groupBy { it.cause }.mapValues { (_, l) -> l.size }.toList().sortedByDescending { it.second }
            Text("What makes it hot: " + byCause.joinToString(" · ") { (c, n) -> "${c.who} $n" }, style = MaterialTheme.typography.titleSmall)
            events.take(6).forEach { e -> HeatEventRow(e, names[e.lat to e.lon], ctx) }
        }
        Muted("This sensor reads the engine head (air/oil cooled), so 120–140 °C in traffic is normal; " +
            "${Heat.HOT} °C+ is noted and ${Heat.VERY_HOT} °C+ shows on your dash.")
    }
}

/** This month in one strip (km, rides, riding time, score) with the change against last month. */
@Composable
fun MonthStrip(rides: List<RideMonthEntry>, fills: List<FillUp>) {
    val r = remember(rides, fills) { MonthlyReports.build(rides, fills) }
    val monthName = remember(r.month) { java.text.DateFormatSymbols(Locale.getDefault()).months[r.month] }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(monthName.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                r.prevKm?.takeIf { it > 0 }?.let { prev ->
                    val change = (r.km - prev) * 100 / prev
                    Muted("${if (change >= 0) "+" else ""}${decimal(change, 0)}% vs last month")
                }
            }
            TextButton(onClick = hapticAction({ expanded = !expanded }), modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Text("${decimal(r.km, 0)} km · ${r.rides} rides", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "Collapse monthly summary" else "Expand monthly summary")
            }
            if (expanded) Box(Modifier.padding(12.dp)) {
                StatGrid(listOf("Distance · km" to decimal(r.km, 0), "Rides" to "${r.rides}",
                    "Moving time" to duration(r.movingS), "Average score" to (r.avgScore?.let { "$it / 100" } ?: "—")))
            }
        }
    }
}
