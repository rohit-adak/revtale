@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.revtale

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class RideRow(val file: File, val summary: RideSummary?, val error: String? = null, val score: RideScore? = null)

private fun rideStart(file: File): Date = runCatching { SimpleDateFormat("'ride-'yyyyMMdd-HHmmss", Locale.US).parse(file.nameWithoutExtension) }.getOrNull()
    ?: Date(file.lastModified())

private fun rideDate(file: File): String = SimpleDateFormat("EEE, d MMM yyyy · HH:mm", Locale.getDefault()).format(rideStart(file))

@Composable
fun RideListScreen(ui: LinkUiController, onOverview: () -> Unit = {}, onRide: (File) -> Unit) {
    var rows by remember { mutableStateOf<List<RideRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) {
        loading = true; error = null
        try {
            val files = ui.rides()
            rows = files.map { RideRow(it, null) }
            for (file in files) {
                val row = try { ui.loadRide(file).let { RideRow(file, it.summary, score = it.score) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { RideRow(file, null, "Could not read this ride. Tap to retry.") }
                rows = rows.map { if (it.file == file) row else it }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Ride history is unavailable. Tap Refresh to try again." }
        finally { loading = false }
    }
    // the ride in progress refreshes itself every 5 s
    val liveFile = ui.state.rideFile
    LaunchedEffect(liveFile, loading) {
        if (liveFile == null || loading) return@LaunchedEffect
        if (rows.none { it.file == liveFile }) { refresh++; return@LaunchedEffect }
        while (true) {
            kotlinx.coroutines.delay(5000)
            val summary = runCatching { ui.loadRide(liveFile).summary }.getOrNull() ?: continue
            rows = rows.map { if (it.file == liveFile) it.copy(summary = summary) else it }
        }
    }
    val total = rows.mapNotNull { it.summary }.sumOf { it.distanceKm }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            // compact header: what this tab is, and the totals in one line
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Rides", style = MaterialTheme.typography.headlineLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Muted("${rows.size} rides · ${decimal(total)} km")
            }
        }
        item {
            var fills by remember { mutableStateOf<List<FillUp>>(emptyList()) }
            LaunchedEffect(ui.notice) { fills = ui.fills() }
            val entries = rows.mapNotNull { r -> r.summary?.let { RideMonthEntry(rideStart(r.file).time, it.distanceKm, it.movingS, it.topSpeed, r.score?.score) } }
            MonthStrip(entries, fills)
            // the exact month total for the home-screen widget
            LaunchedEffect(entries) {
                if (!loading) {
                    val r = MonthlyReports.build(entries, fills)
                    ui.prefs.edit().putString("monthKey", "%04d%02d".format(r.year, r.month + 1)).putFloat("monthKm", r.km.toFloat()).apply()
                    BikeWidget.refresh(ui.context)
                }
            }
        }
        if (rows.size >= 2) item {
            Surface(onClick = hapticAction(onOverview), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)),
                modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Insights, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text("How you ride", style = MaterialTheme.typography.titleMedium)
                        Muted("Routes, parking, gears, engine heat")
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            var fuelOpen by rememberSaveable { mutableStateOf(false) }
            OutlinedCard(onClick = hapticAction({ fuelOpen = !fuelOpen }), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.LocalGasStation, null)
                    Text("Fuel & mileage", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Icon(if (fuelOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (fuelOpen) "Collapse fuel log" else "Expand fuel log")
                }
            }
            if (fuelOpen) { Spacer(Modifier.height(12.dp)); FuelCard(ui) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Ride history", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                TextButton(onClick = hapticAction({ refresh++ }), enabled = !loading) { Icon(Icons.Outlined.Refresh, null); Text("Refresh") }
            }
        }
        if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Loading saved rides" }) }
        error?.let { item { RaceCard { Text(it) } } }
        if (rows.isEmpty() && !loading && error == null) item {
            RaceCard {
                Icon(Icons.Outlined.Route, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                SectionTitle("Your first ride starts here.", "Connect your bike and ride with recording enabled. Your ride appears here live as you go.")
                if (!ui.state.recordRides) Button(onClick = hapticAction({ ui.setPreference("recordRides", true) })) { Text("Enable ride recording") }
            }
        }
        // grouped by month, one slim row per ride
        rows.groupBy { java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(rideStart(it.file)) }.forEach { (month, list) ->
            item(key = "m-$month") {
                Text(month.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 6.dp))
            }
            items(list, key = { it.file.name }) { row -> RideRowCompact(row, row.file == liveFile) { onRide(row.file) } }
        }
    }
}

@Composable
fun RideDetailScreen(ui: LinkUiController, file: File, onBack: () -> Unit) {
    var data by remember(file.path) { mutableStateOf<RideData?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var tracePosition by rememberSaveable(file.path) { mutableFloatStateOf(0f) }
    var showAllSpots by rememberSaveable(file.path) { mutableStateOf(false) }
    val live = ui.state.rideFile == file
    LaunchedEffect(file.path, retry, live) {
        error = null
        try { data = ui.loadRide(file) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "This ride could not be read. It may have been removed or is still being saved." }
        // ride in progress: keep the analysis current
        while (live) {
            kotlinx.coroutines.delay(5000)
            runCatching { ui.loadRide(file) }.getOrNull()?.let { data = it }
        }
    }
    val ride = data
    var section by rememberSaveable(file.path) { mutableIntStateOf(0) }
    val detailStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = hapticAction(onBack), modifier = Modifier.padding(horizontal = 8.dp)) {
            Icon(Icons.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("All rides")
        }
        ScrollableTabRow(selectedTabIndex = section, edgePadding = 16.dp,
            containerColor = MaterialTheme.colorScheme.background) {
            listOf("Overview", "Charts", "Gears", "Route").forEachIndexed { index, label ->
                Tab(selected = section == index, onClick = hapticAction({ section = index }), text = { Text(label) })
            }
        }
        detailStates.SaveableStateProvider(section) {
        LazyColumn(Modifier.weight(1f), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading(if (live) "Live ride" else "Ride analysis", "Ride details",
            rideDate(file) + if (live) " · updating every 5 s" else "") }
        if (error != null) item { RaceCard { Text(error!!); OutlinedButton(onClick = hapticAction({ retry++ })) { Text("Retry") } } }
        else if (ride == null) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Loading ride analysis" }) }
        if (ride != null) {
            val summary = ride.summary
            if (section == 0) {
            item {
                RaceCard {
                    Eyebrow("Top speed")
                    Text("${summary.topSpeed}", style = MaterialTheme.typography.displayLarge)
                    Text("km/h", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) { Text("${decimal(summary.distanceKm)} km", style = MaterialTheme.typography.titleLarge); Muted("Distance") }
                        Column(Modifier.weight(1f)) { Text(duration(summary.durationS), style = MaterialTheme.typography.titleLarge); Muted("Elapsed time") }
                    }
                }
            }
            ride.score?.let { sc -> item { ScoreCard(sc) } }
            ride.heat?.let { h -> item { HeatCard(h, ride.samples.firstOrNull()?.tMs ?: 0L) } }
            if (!live) item { ShareRideRow(ui, file) }
            item { StatGrid(listOf(
                "Moving time" to duration(summary.movingS), "Avg moving · km/h" to decimal(summary.avgMovingSpeed),
                "Maximum RPM" to "${summary.maxRpm}", "Best 0–60 km/h" to (summary.best0to60S?.let { "${decimal(it)} s" } ?: "Not recorded"),
                "Engine stops" to "${summary.engineStops}", "Idle time" to duration(summary.idleS),
                "Hard brakes" to "${summary.hardBrakes}", "Hard launches" to "${summary.hardLaunches}",
                "At / above 9,000 RPM" to duration(summary.highRpmS), "Time moving" to "${decimal(if (summary.durationS > 0) summary.movingS * 100 / summary.durationS else 0.0, 0)}%"
            )) }
            if (summary.modeS.isNotEmpty()) item {
                RaceCard {
                    SectionTitle("Riding modes", "Moving time in each mode the bike reported.")
                    val modeTotal = summary.modeS.values.sum()
                    summary.modeS.forEach { (mode, secs) ->
                        MetricRow(RideModes.name(mode), "${duration(secs)} · ${decimal(if (modeTotal > 0) secs * 100 / modeTotal else 0.0, 0)}%")
                    }
                }
            }
            item { HiddenInsights(ride.insights, summary) }
            item { Muted("0–60 is estimated from recorded samples. Engine stops include traffic stops and the kill switch. Hard events use a 10 km/h change per second.") }
            }
            if (section == 1) {
            item { SectionTitle("Speed & RPM", "Speed and RPM over elapsed ride time. Background bands show the selected gear.") }
            item { RideTrace(ride.samples, rpm = false) }
            item { RideTrace(ride.samples, rpm = true) }
            if (ride.samples.isNotEmpty()) item {
                val index = (tracePosition * ride.samples.lastIndex).toInt().coerceIn(0, ride.samples.lastIndex)
                val sample = ride.samples[index]
                RaceCard {
                    SectionTitle("Inspect a moment")
                    Text("${duration((sample.tMs - ride.samples.first().tMs) / 1000.0)} · ${sample.speedKmh} km/h · ${sample.rpm} RPM · ${if (sample.gear == 0) "Neutral" else "Gear ${sample.gear}"}")
                    Slider(value = tracePosition, onValueChange = hapticSlider(tracePosition) { tracePosition = it }, modifier = Modifier.semantics { contentDescription = "Inspect ride timeline" })
                }
            }
            item { HistogramCard("Speed distribution", "Time moving in each 10 km/h band", summary.speedHistogram, 10, "km/h") }
            item { HistogramCard("RPM distribution", "Engine-running time in each 1,000 RPM band", summary.rpmHistogram, 1000, "RPM") }
            }
            if (section == 2) {
            item { SectionTitle("Gear by gear", "Time share is measured across forward gears.") }
            if (summary.gears.isEmpty()) item { RaceCard { Muted("No forward gear data was recorded.") } }
            items(summary.gears, key = { "gear-${it.gear}" }) { gear ->
                RaceCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("GEAR ${gear.gear}", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text("${decimal(gear.sharePct, 0)}%", style = MaterialTheme.typography.titleLarge)
                    }
                    LinearProgressIndicator(progress = { (gear.sharePct / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Gear ${gear.gear} time share ${decimal(gear.sharePct)} percent" })
                    MetricRow("Time in gear", duration(gear.seconds))
                    MetricRow("Top speed", "${gear.topSpeed} km/h")
                    MetricRow("Max / average RPM", "${gear.maxRpm} / ${gear.avgRpm}")
                    MetricRow("km/h per 1,000 RPM", decimal(gear.kmhPer1000Rpm))
                    MetricRow("Shift to gear ${gear.gear + 1}", summary.shiftUpRpm[gear.gear]?.let { "$it RPM" } ?: "Not recorded")
                }
            }
            item {
                RaceCard {
                    SectionTitle("Shift points", "Median RPM as you shifted up to the next gear.")
                    if (summary.shiftUpRpm.isEmpty()) Muted("No upshifts were recorded.")
                    summary.shiftUpRpm.toSortedMap().forEach { (gear, rpm) -> MetricRow("$gear → ${gear + 1}", "$rpm RPM") }
                }
            }
            }
            if (section == 3) {
            item { SectionTitle("Route & spots", "Route coloured by speed. Numbered markers identify the fastest recorded spots.") }
            item { RideRouteMap(ride.samples, summary.spots) }
            item {
                RaceCard {
                    SectionTitle("Fastest spots", "Highest recorded speeds first. Coordinates stay on your phone.")
                    if (summary.spots.isEmpty()) Muted("No GPS spots recorded. Enable precise location before your next ride.")
                }
            }
            val visibleSpots = summary.spots.sortedByDescending { it.topSpeed }.let { if (showAllSpots) it else it.take(10) }
            items(visibleSpots.size, key = { "spot-$it" }) { index ->
                val spot = visibleSpots[index]
                RaceCard {
                    Text("${index + 1}. ${spot.topSpeed} km/h peak · ${spot.avgSpeed} km/h average", style = MaterialTheme.typography.titleMedium)
                    Muted("${decimal(spot.lat, 5)}, ${decimal(spot.lon, 5)} · ${spot.passes} samples")
                }
            }
            if (summary.spots.size > 10) item {
                TextButton(onClick = hapticAction({ showAllSpots = !showAllSpots })) {
                    Text(if (showAllSpots) "Show top 10" else "Show all ${summary.spots.size} spots")
                }
            }
            }
            if (section == 0) item {
                RaceCard {
                    Eyebrow("At this pace")
                    SectionTitle("How far could you go?", "Projection at your ${decimal(summary.avgMovingSpeed)} km/h moving average, excluding stops.")
                    listOf(30, 60, 120).forEach { minutes ->
                        MetricRow("$minutes minutes", "${decimal(RideStats.reachKm(summary.avgMovingSpeed, minutes.toDouble()))} km")
                    }
                    HorizontalDivider()
                    val eta = RideStats.etaMinutes(100.0, summary.avgMovingSpeed)
                    MetricRow("100 km at this pace", eta?.let { duration(it * 60) } ?: "Unavailable")
                    Muted("An estimate from this ride, not a target or a live arrival prediction.")
                }
            }
        }
    }
    }
    }
}

/** Findings the raw numbers hide: each one is a headline and the evidence behind it. */
@Composable
private fun HiddenInsights(i: RideInsights, summary: RideSummary) {
    RaceCard {
        Eyebrow("Hidden in plain sight")
        SectionTitle("What your ride says", "Worked out from the same samples as everything else on this page.")
        i.speedoErrorPct?.let { e ->
            val sixty = 60 / (1 + e / 100)
            Insight(Icons.Outlined.Speed,
                if (kotlin.math.abs(e) < 2) "Your speedometer is spot on" else "Your speedometer reads ${decimal(kotlin.math.abs(e), 0)}% ${if (e > 0) "high" else "low"}",
                "60 on the dial ≈ ${decimal(sixty, 0)} km/h by GPS · from ${i.speedoSamples} steady stretches above 25 km/h")
        }
        if (summary.distanceKm > 0.2) Insight(Icons.Outlined.Traffic,
            if (i.stops == 0) "No stops" else "${i.stops} stops · ${duration(i.stoppedS)} standing still",
            if (i.stops == 0) "Not a single halt of 2 s or more." else
                "Longest wait ${duration(i.longestStopS)} · one stop every ${decimal(summary.distanceKm / i.stops)} km")
        if (i.upshifts + i.downshifts > 0) Insight(Icons.Outlined.SwapVert,
            "${i.upshifts + i.downshifts} gear changes",
            "${i.upshifts} up · ${i.downshifts} down" + if (summary.distanceKm > 0.5) " · ${decimal((i.upshifts + i.downshifts) / summary.distanceKm)} per km" else "")
        i.clutchPct?.let { c ->
            Insight(Icons.Outlined.PanTool, "Clutch pulled ${decimal(c, 0)}% of the time you were rolling",
                "Coasting in neutral or with the lever in. In gear with the throttle shut (engine braking): ${decimal(i.engineBrakePct, 0)}%.")
        }
        i.throttleClosedPct?.let { t ->
            Insight(Icons.Outlined.Tune, "Throttle shut ${decimal(t, 0)}% of moving time",
                "Average opening ${decimal(i.avgThrottle, 0)} on the bike's own scale while moving.")
        }
        i.smoothPct?.let { sm ->
            Insight(Icons.Outlined.Waves, "${decimal(sm, 0)}% smooth seconds",
                "Speed changed by 3 km/h or less in that second. Hard brakes: ${summary.hardBrakes}, hard launches: ${summary.hardLaunches}.")
        }
        if (i.best0to40S != null || i.fastestKmS != null) Insight(Icons.Outlined.Bolt,
            listOfNotNull(i.best0to40S?.let { "0–40 in ${decimal(it)} s" }, i.fastestKmS?.let { "fastest km ${duration(it)}" }).joinToString(" · ").replaceFirstChar { it.uppercase() },
            i.fastestKmS?.let { "That kilometre averaged ${decimal(3600 / it, 0)} km/h." } ?: "Measured from a standstill.")
        if (i.voltsIdle != null && i.voltsRevving != null) Insight(Icons.Outlined.BatteryChargingFull,
            "Battery ${decimal(i.voltsIdle)} V at idle → ${decimal(i.voltsRevving)} V above 4,000 RPM",
            "Range ${decimal(i.voltsMin)}–${decimal(i.voltsMax)} V. Around 13–14.8 V when revving is what a charging system normally gives.")
        if (i.modes.isNotEmpty()) Insight(Icons.Outlined.Layers,
            "Riding modes compared",
            i.modes.entries.joinToString("\n") { (m, u) ->
                "${RideModes.name(m)} · ${duration(u.seconds)} · avg ${decimal(u.avgSpeed, 0)} km/h · throttle ${decimal(u.avgThrottle, 0)} · ${u.avgRpm} RPM"
            })
    }
}

@Composable
private fun Insight(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(detail)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Muted(label, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    }
}

/** All rides together: your usual routes, where you parked last, and how you ride each gear. */
@Composable
fun RideOverviewScreen(ui: LinkUiController, onBack: () -> Unit) {
    var data by remember { mutableStateOf<OverviewData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { data = ui.loadOverview() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Your rides could not be read." }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun name(lat: Double, lon: Double) = data?.names?.get(lat to lon) ?: "%.4f, %.4f".format(Locale.US, lat, lon)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { TextButton(onClick = hapticAction(onBack)) { Icon(Icons.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("All rides") } }
        item { PageHeading("Every ride together", "How you ride.", data?.let { "Average ${decimal(it.overview.avgMovingSpeed)} km/h while moving." }) }
        val d = data
        if (error != null) item { RaceCard { Text(error!!) } }
        else if (d == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else {
            val o = d.overview
            item {
                StatGrid(listOf("Rides" to "${o.rides}", "Distance" to "${decimal(o.km)} km",
                    "Riding time" to duration(o.movingS), "Top speed" to "${o.topSpeed} km/h"))
            }
            o.parked?.let { p ->
                item {
                    RaceCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Eyebrow("Last parked")
                            Icon(Icons.Outlined.LocalParking, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(name(p.lat, p.lon), style = MaterialTheme.typography.titleLarge)
                        Muted(SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(p.atMs)))
                        OutlinedButton(onClick = hapticAction({
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(My bike)"))) }
                        })) { Icon(Icons.Outlined.Map, null); Spacer(Modifier.width(8.dp)); Text("Show in Maps") }
                    }
                }
            }
            item {
                RaceCard {
                    SectionTitle("Your usual routes", "Trips you make again and again (start and end within ~250 m).")
                    if (o.routes.isEmpty()) Muted("Ride the same trip twice and it shows up here.")
                    o.routes.forEach { r ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${name(r.from.lat, r.from.lon)} → ${name(r.to.lat, r.to.lon)}", style = MaterialTheme.typography.titleMedium)
                            Muted("${r.count}× · ${decimal(r.avgKm)} km · about ${r.avgMinutes.toInt()} min · ${decimal(r.avgSpeed)} km/h moving")
                            val saved = ui.places.any { it.lat == r.to.lat && it.lon == r.to.lon }
                            TextButton(onClick = hapticAction({
                                ui.savePlace(SavedPlace(name(r.to.lat, r.to.lon).substringBefore(',').take(20), r.to.lat, r.to.lon,
                                    from = name(r.from.lat, r.from.lon).substringBefore(',').take(20)))
                            }), enabled = !saved, contentPadding = PaddingValues(0.dp)) {
                                Text(if (saved) "Saved on Live → Ride to" else "Save route for one-tap navigation")
                            }
                        }
                    }
                    if (o.places.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Places you ride from and to", style = MaterialTheme.typography.titleSmall)
                        o.places.forEach { pl -> Muted("${name(pl.lat, pl.lon)} · ${pl.visits}×") }
                    }
                }
            }
            if (d.mapSamples.isNotEmpty()) item { RideRouteMap(d.mapSamples, emptyList()) }
            item {
                RaceCard {
                    SectionTitle("Gear by gear", "How you use each gear across every ride.")
                    if (o.gears.isEmpty()) Muted("No gear data yet.")
                    o.gears.forEach { g ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text("${g.gear}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${g.speedLow}–${g.speedHigh} km/h · ${decimal(g.sharePct, 0)}% of riding", style = MaterialTheme.typography.titleMedium)
                                Muted("avg ${g.avgSpeed} km/h at ${g.avgRpm} RPM · top ${g.topSpeed} km/h" +
                                    (g.shiftUpRpm?.let { " · shift up ~$it RPM" } ?: ""))
                            }
                        }
                    }
                }
            }
            item { HeatOverviewCard(d.heats, d.names) }
            item { HistogramCard("Speeds you ride at", "Time moving in each 10 km/h band, all rides", o.speedHistogram, 10, "km/h") }
            item { HistogramCard("RPM you ride at", "Engine-running time in each 1,000 RPM band, all rides", o.rpmHistogram, 1000, "RPM") }
        }
    }
}

/** One ride in the list: date block, distance and time, top speed, score; tap for the full debrief. */
@Composable
private fun RideRowCompact(row: RideRow, live: Boolean, onClick: () -> Unit) {
    val start = remember(row.file) { rideStart(row.file) }
    val day = remember(start) { SimpleDateFormat("d", Locale.getDefault()).format(start) }
    val dow = remember(start) { SimpleDateFormat("EEE", Locale.getDefault()).format(start).uppercase() }
    val time = remember(start) { SimpleDateFormat("h:mm a", Locale.getDefault()).format(start) }
    Surface(onClick = hapticAction(onClick), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day, style = MaterialTheme.typography.titleLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(dow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val sm = row.summary
                Text(if (sm != null) "${decimal(sm.distanceKm)} km · ${duration(sm.durationS)}" else (row.error ?: "Reading ride…"),
                    style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(time + (sm?.let { " · top ${it.topSpeed} km/h" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live) Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                Text("LIVE", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
