@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
private fun ClusterPreview(value: String) {
    val display = Protocol.clusterText(value).take(17)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CLUSTER PREVIEW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val size = (maxWidth.value / 11f / LocalDensity.current.fontScale).coerceAtMost(20f)
                Text(display.padEnd(17, ' '), fontFamily = FontFamily.Monospace, fontSize = size.sp, maxLines = 1,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = "Cluster preview: ${display.ifBlank { "empty" }}" })
            }
        }
    }
}

@Composable
fun ClusterScreen(ui: LinkUiController) {
    val state = ui.state
    var name by rememberSaveable { mutableStateOf(ui.prefs.getString("name", "RIDER") ?: "RIDER") }
    Column(Modifier.fillMaxSize()) {
    // emulator running: show what each feature puts on the (virtual) cluster

    LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.simulating) item { EmulatorCluster(ui) }
        item {
            PageHeading("Bike display", "Cluster", "Choose what appears on your dashboard.")
            if (!state.connected) Muted("Connect your bike to send changes. Preferences can be saved now.")
        }
        item {
            // your name on the dash, in one slim row
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Badge, null, tint = MaterialTheme.colorScheme.primary)
                    TextField(name, { name = it.take(17) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Name on the dash") },
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    ClusterPreview(name)
                    FilledTonalButton(onClick = hapticAction({ ui.saveName(name) }), enabled = name.isNotBlank()) { Text("Save rider name") }
                }
            }
        }
        item { ClusterFeatures(ui) }
    }
    }
}

/** Everything the cluster can show, one line each: switch it on, and fix the permission if Android blocks it. */
@Composable
private fun ClusterFeatures(ui: LinkUiController) {
    val ctx = ui.context
    val state = ui.state
    var calls by remember { mutableStateOf(ui.prefs.getBoolean("callsToCluster", true)) }
    var gemini by remember { mutableStateOf(ui.prefs.getBoolean("assistantOnHold", false)) }
    // re-read what Android allows each time the screen refreshes (the rider may come back from Settings)
    val notifAccess = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val overlay = android.provider.Settings.canDrawOverlays(ctx)
    val callsMissing = ui.callPermissionsMissing().isNotEmpty()
    RaceCard {
        FeatureRow(Icons.Outlined.TurnRight, "Directions", "Google Maps turns, distance and road", state.mapsToCluster,
            { ui.setPreference("mapsToCluster", it) },
            blocked = if (notifAccess) null else "Needs notification access",
            onFix = { ui.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        HorizontalDivider()
        var scroll by remember { mutableStateOf(ui.prefs.getBoolean("scrollText", true)) }
        SettingSwitch("Scroll long text", "Road names, Maps' directions and texts longer than the dash's line scroll through it like an LED sign. Off: cut to fit.",
            scroll) { scroll = it; ui.setPreference("scrollText", it) }
        HorizontalDivider()
        // what the dash shows when no route runs
        var idle by remember { mutableStateOf((BleService.instance?.idleLine() ?: ui.prefs.getString("idleLine", null) ?: "trip")
            .split(',').filter { it in BleService.IDLE_KINDS }.toSet()) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.Explore, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("When no route runs", style = MaterialTheme.typography.titleMedium)
                Text(if (idle.isEmpty()) "Nothing" else listOfNotNull(
                        "RIDE 12KM 18MIN".takeIf { "trip" in idle }, "GOING EAST >>".takeIf { "compass" in idle },
                        "ON NH 48".takeIf { "area" in idle }).joinToString(" · ") +
                        (if (idle.size > 1) ", taking turns" else "") + (if ("area" in idle) ". Area needs internet." else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // pick any mix; they take turns on the line
                    listOf("trip" to "Ride so far", "compass" to "Direction", "area" to "Area").forEach { (k, label) ->
                        FilterChip(k in idle, hapticSelection({
                            idle = if (k in idle) idle - k else idle + k
                            ui.setText("idleLine", idle.joinToString(",").ifEmpty { "off" })
                        }), label = { Text(label) })
                    }
                }
            }
        }
        HorizontalDivider()
        FeatureRow(Icons.Outlined.Call, "Calls", "Caller's name · press i to decline · hold i to text back", calls,
            { calls = it; ui.setPreference("callsToCluster", it) },
            blocked = if (callsMissing) "Needs phone and SMS permissions" else null, onFix = ui::askCallPermissions) {
            TextBackMessage(ui)
        }
        HorizontalDivider()
        FeatureRow(Icons.Outlined.Mic, "Gemini", "Hold i when nothing is on the dash", gemini,
            { gemini = it; ui.setPreference("assistantOnHold", it) },
            blocked = if (overlay) null else "Needs \"Display over other apps\"",
            onFix = { ui.openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) })
        HorizontalDivider()
        FeatureRow(Icons.Outlined.PhoneAndroid, "Phone status", "Clock, battery and signal", state.autoLink,
            { ui.setPreference("autoLink", it) })
        HorizontalDivider()
        var weather by remember { mutableStateOf(ui.prefs.getBoolean("weatherOnCluster", true)) }
        FeatureRow(Icons.Outlined.Cloud, "Weather first", if (ui.weatherKey.isBlank()) "Add your free Weather Union key under Options to switch it on"
            else "Before a route's first turn: rain and temperature where you're going", weather,
            { weather = it; ui.setPreference("weatherOnCluster", it) }) { WeatherKeyField(ui) }
        HorizontalDivider()
        var eta by remember { mutableStateOf(ui.prefs.getBoolean("etaOnCluster", true)) }
        FeatureRow(Icons.Outlined.Schedule, "Arrival time", "When the next turn is 2 km+ away, ETA and minutes left take turns with the road", eta,
            { eta = it; ui.setPreference("etaOnCluster", it) })
        HorizontalDivider()
        var trip by remember { mutableStateOf(ui.prefs.getBoolean("rideSummaryOnCluster", true)) }
        FeatureRow(Icons.Outlined.Flag, "Trip summary", "Engine off after a ride: km, time and average speed for 10 s", trip,
            { trip = it; ui.setPreference("rideSummaryOnCluster", it) })
        HorizontalDivider()
        var heat by remember { mutableStateOf(ui.prefs.getBoolean("heatOnCluster", true)) }
        FeatureRow(Icons.Outlined.Thermostat, "Engine heat warning", "ENGINE HOT on the dash when it stays at ${Heat.VERY_HOT} °C+ for 30 s", heat,
            { heat = it; ui.setPreference("heatOnCluster", it) })
        HorizontalDivider()
        var parked by remember { mutableStateOf(ui.prefs.getBoolean("parkedAlert", true)) }
        FeatureRow(Icons.Outlined.LocalParking, "Where you parked", "A quiet notification when you switch off, with Walk there", parked,
            { parked = it; ui.setPreference("parkedAlert", it) })
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, checked: Boolean,
    onChange: (Boolean) -> Unit, blocked: String? = null, onFix: (() -> Unit)? = null,
    more: (@Composable () -> Unit)? = null
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked, hapticToggle(onChange), modifier = Modifier.semantics { contentDescription = title })
        }
        if (checked && blocked != null && onFix != null) TextButton(onClick = hapticAction(onFix), contentPadding = PaddingValues(start = 38.dp)) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("$blocked · Allow")
        }
        if (checked && more != null) {
            TextButton(onClick = hapticAction({ open = !open }), contentPadding = PaddingValues(start = 38.dp)) { Text(if (open) "Hide $title options" else "$title options") }
            if (open) Box(Modifier.fillMaxWidth()) { more() }
        }
    }
}

@Composable
private fun TextBackMessage(ui: LinkUiController) {
    var sms by remember { mutableStateOf(BleService.instance?.ridingSms ?: ui.prefs.getString("callBackSms", null) ?: BleService.DEFAULT_SMS) }
    OutlinedTextField(sms, { sms = it.take(160); ui.setText("callBackSms", sms) }, label = { Text("Text-back message") },
        supportingText = { Text("Their reply shows on the dash until you press i.") }, modifier = Modifier.fillMaxWidth())
}

/** App version and updates (from the project's GitHub releases). */
@Composable
private fun UpdatesCard(ui: LinkUiController) {
    val feedback = rememberLinkFeedback()
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                var taps by remember { mutableStateOf(0) }
                Text(ui.update?.let { "Version ${it.version} is ready" } ?: "Version ${ui.appVersion}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        if (ui.devUnlocked) return@clickable
                        feedback.selection()
                        taps++
                        if (taps >= 7) { ui.unlockDeveloper(); ui.notice = "Developer options unlocked" }
                        else if (taps >= 4) ui.notice = "${7 - taps} more taps for developer options"
                    })
                Muted(ui.prefs.getString("updateStatus", null) ?: "Updates are checked automatically.")
            }
            if (ui.update != null) Button(onClick = hapticAction(ui::installUpdate), enabled = ui.updateProgress == null) { Text("Update") }
            else OutlinedButton(onClick = hapticAction({ ui.checkForUpdate(manual = true) }), enabled = !ui.updateChecking) {
                Text(if (ui.updateChecking) "Checking…" else "Check")
            }
        }
        ui.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
    }
}

/** "Version x is available" once per found release, never while riding. */
@Composable
fun UpdateDialog(ui: LinkUiController) {
    val u = ui.update ?: return
    AlertDialog(onDismissRequest = { ui.updateLater = true },
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RevTale ${u.version} is out (you have ${ui.appVersion}).")
                if (u.notes.isNotBlank()) Muted(u.notes.take(600))
                ui.updateProgress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onClick = hapticAction(ui::installUpdate), enabled = ui.updateProgress == null) { Text("Update") } },
        dismissButton = { TextButton(onClick = hapticAction({ ui.updateLater = true })) { Text("Later") } })
}

@Composable
private fun NavigationCard(ui: LinkUiController) {
    RaceCard {
        Icon(Icons.Outlined.TurnRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        SectionTitle("Your next turn", "Google Maps directions, mirrored on your bike.")
        SettingSwitch("Directions on cluster", "Uses notification access to read navigation instructions.", ui.state.mapsToCluster) { ui.setPreference("mapsToCluster", it) }
        SettingSwitch("Compass on cluster", "Shows the direction you're heading (e.g. NORTH EAST) when no route is running.", ui.state.compassOnCluster) { ui.setPreference("compassOnCluster", it) }
        OutlinedButton(onClick = hapticAction({ ui.openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }), modifier = Modifier.fillMaxWidth()) {
            Text("Google Maps directions on the cluster")
        }
        if (ui.state.nav.isNotBlank()) Text(ui.state.nav)
        TextButton(onClick = hapticAction(ui::navDemo), enabled = ui.state.connected) { Text("Nav demo · right turn in 100 m") }
        TextButton(onClick = hapticAction({ ui.bikeCommand { ui.stopDemo(); it.stopNavigation() } }), enabled = ui.state.connected) { Text("Stop navigation") }
        Muted("Enable RevTale on the notification access page. The cluster shows the arrow, the distance and the road you turn onto.")
    }
}

@Composable
fun SettingsScreen(ui: LinkUiController, onChangeBike: () -> Unit, onDeveloper: () -> Unit, onCalibrate: () -> Unit) {
    val state = ui.state
    LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageHeading("Preferences", "Settings", "Your bike, app appearance and data.") }
        item { BikesCard(ui, onChangeBike) }
        item { CalibrationCard(ui, onCalibrate) }
        item {
            RaceCard {
                SettingSwitch("Record rides", "Speed, gears and route, kept on this phone.", state.recordRides) { ui.setPreference("recordRides", it) }
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "Follow phone", "dark" to "Dark", "light" to "Light").forEach { (mode, label) ->
                        FilterChip(state.theme == mode, onClick = hapticAction({ ui.setTheme(mode) }), label = { Text(label) })
                    }
                }
            }
        }
        item { BackgroundCard(ui) }
        item { BackupCard(ui) }
        item { UpdatesCard(ui) }
        item { ReportCard(ui) }
        // Developer stays hidden (friends get a plain app); tap the version line 7 times to show it, like Android
        if (ui.devUnlocked) item {
            OutlinedCard(onClick = hapticAction(onDeveloper), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Outlined.Code, null)
                    Text("Developer", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        item { Muted("RevTale · independent companion app, not affiliated with any bike maker. Your data stays on this phone.") }
    }
}

/** Developer sections, one tab each, so each tool has its own page instead of one long scroll. */
private enum class DevTab(val label: String) { STATUS("Status"), EMULATOR("Emulator"), BIKE("Bike"), CLUSTER("Cluster"), NAV("Nav"), DATA("Data"), LOG("Log") }

@Composable
fun DeveloperScreen(ui: LinkUiController, onBack: () -> Unit) {
    val feedback = rememberLinkFeedback()
    var saved by rememberSaveable { mutableStateOf(DevTab.STATUS.ordinal) }
    // tabs are pages: swipe between them, or tap the tab row
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = saved) { DevTab.entries.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) {
        if (saved != pager.currentPage) feedback.selection()
        saved = pager.currentPage
    }
    val state = ui.state
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = hapticAction(onBack)) { Icon(Icons.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Settings") }
            Spacer(Modifier.weight(1f))
            StatusBadge(when { state.simulating -> "Emulator"; state.connected -> "Connected"; state.session -> "Searching"; else -> "Offline" }, state.connected)
        }
        ScrollableTabRow(pager.currentPage, edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.background) {
            DevTab.entries.forEach { t -> Tab(pager.currentPage == t.ordinal,
                onClick = hapticAction({ scope.launch { pager.animateScrollToPage(t.ordinal) } }), text = { Text(t.label) },
                selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val tab = DevTab.entries[page]
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (state.simulating && tab in listOf(DevTab.EMULATOR, DevTab.CLUSTER, DevTab.NAV))
                        item { EmulatorCluster(ui) }
                    when (tab) {
                        DevTab.STATUS -> devStatus(ui)
                        DevTab.EMULATOR -> devEmulator(ui)
                        DevTab.BIKE -> devBike(ui)
                        DevTab.CLUSTER -> item { DevClusterLab(ui) }
                        DevTab.NAV -> { item { TestDirections(ui) }; item { DevArrowTest(ui) }; item { NavigationCard(ui) } }
                        DevTab.DATA -> devData(ui)
                        DevTab.LOG -> devLog(ui)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.devStatus(ui: LinkUiController) {
    val state = ui.state
    item {
        RaceCard {
            SectionTitle("Link", state.devSelected?.let { "Bike $it" } ?: "No bike selected")
            if (state.devStatus.isEmpty()) Muted("Service is off. Connect from Live or the Bike tab.")
            else StatGrid(state.devStatus)
        }
    }
    item {
        RaceCard {
            SectionTitle("Decoded now", "What the app reads from the frames, before any smoothing.")
            val l = state.live
            StatGrid(listOf(
                "Speed" to (l.speed?.let { "$it km/h" } ?: "—"), "RPM" to (l.rpm?.toString() ?: "—"),
                "Gear" to (l.gear?.let { if (it == 0) "N" else "$it" } ?: "—"), "Mode" to (l.mode?.let(RideModes::name) ?: "—"),
                "Battery" to (l.volts?.let { "${decimal(it)} V" } ?: "—"), "Engine" to (l.temperature?.let { "$it°" } ?: "—")))
        }
    }
    item {
        RaceCard {
            SectionTitle("Recording")
            SettingSwitch("Detailed research log", "Raw frames, GPS, sensors and Maps notification text.", state.detailedLog) { ui.setPreference("detailedLog", it) }
            OutlinedButton(onClick = hapticAction(ui::saveLog), enabled = !ui.busy && state.session) { Text("End & save log") }
            TextButton(onClick = hapticAction(ui::shareLog)) { Text("Share last log") }
            Muted("Logs can include your route. Share only with people you choose.")
        }
    }
}

private fun LazyListScope.devBike(ui: LinkUiController) {
    item { DevDevices(ui) }
    item {
        RaceCard {
            val s = BleService.instance
            SectionTitle("Connection options", "Apply to the next connection.")
            DeveloperToggle("Pair first", s?.optPairFirst ?: true) { s?.optPairFirst = it }
            DeveloperToggle("Read readable values", s?.optReadAll ?: true) { s?.optReadAll = it }
            DeveloperToggle("Subscribe to notifications", s?.optSubscribeAll ?: true) { s?.optSubscribeAll = it }
            DeveloperToggle("Reconnect on link loss", s?.optAutoReconnect ?: true) { s?.optAutoReconnect = it }
        }
    }
    item {
        RaceCard {
            SectionTitle("Writable characteristics")
            Muted(BleService.instance?.writableChars()?.joinToString("\n")?.ifBlank { "None discovered." } ?: "Service off")
        }
    }
}

@Composable
private fun DevDevices(ui: LinkUiController) {
    var bikesOnly by rememberSaveable { mutableStateOf(true) }
    var selected by remember { mutableStateOf<BleService.Dev?>(null) }
    RaceCard {
        SectionTitle("Devices")
        SettingSwitch("Bikes only", "Turn off to inspect all nearby devices.", bikesOnly) { bikesOnly = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ ui.requestStart(StartAction.FIND) })) { Text("Scan") }
            TextButton(onClick = hapticAction(ui::cancelFinding)) { Text("Stop") }
            TextButton(onClick = hapticAction(ui::disconnect)) { Text("Disconnect") }
        }
        val list = ui.state.devices.filter { !bikesOnly || Bikes.looksLikeBike(it.name) }
        if (list.isEmpty()) Muted(if (ui.state.scanning) "Scanning…" else "No devices yet.")
        list.forEach { d ->
            OutlinedButton(onClick = hapticAction({ selected = d }), modifier = Modifier.fillMaxWidth()) { Text("${d.name ?: "Unnamed"} · ${d.rssi} dBm\n${d.addr}") }
        }
    }
    selected?.let { device -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Use this as your bike?") },
        text = { Text("${device.name ?: "Unnamed"}\n${device.addr}\nOnly confirm your own bike. The passkey will appear on its cluster.") },
        confirmButton = { TextButton(onClick = hapticAction({ ui.chooseBike(device); selected = null })) { Text("Save & connect") } },
        dismissButton = { TextButton(onClick = hapticAction({ selected = null })) { Text("Cancel") } }) }
}

@Composable
private fun DevClusterLab(ui: LinkUiController) {
    var labText by rememberSaveable { mutableStateOf("HELLO RIDER") }
    var feedback by rememberSaveable { mutableStateOf("0") }
    RaceCard {
        SectionTitle("Cluster lab", "Send text to each slot and see where the cluster draws it.")
        OutlinedTextField(labText, { labText = it.take(17) }, label = { Text("Lab text") }, modifier = Modifier.fillMaxWidth())
        ClusterPreview(labText)
        listOf("Custom line" to 0x4C, "Rider name" to 0x52, "Caller" to 0x43, "SMS" to 0x53,
            "Music title" to 0x62, "Music artist" to 0x65, "Music album" to 0x61, "Location" to 0x5C).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, id) -> OutlinedButton(onClick = hapticAction({ ui.bikeCommand { it.sendTextSlot(id, Protocol.clusterText(labText)) } }),
                    modifier = Modifier.weight(1f)) { Text(label) } }
            }
        }
        TextButton(onClick = hapticAction({ ui.bikeCommand { it.showClaude() } })) { Text("Show CLAUDE") }
        HorizontalDivider()
        OutlinedTextField(feedback, { feedback = it.filter(Char::isDigit).take(3) }, label = { Text("Feedback ID · 0–255") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = hapticAction({ ui.bikeCommand { it.sendFeedback(feedback.toInt(), labText) } }), enabled = feedback.toIntOrNull() in 0..255) { Text("Send feedback") }
    }
}

/**
 * "Test directions" (parked): plays every arrow, prefix, unit, alert and feedback code on the cluster, 2 s each, with
 * the number shown on the dash. Film the cluster, share the video, and every code can be mapped from it. While an
 * arrow is showing, the buttons below save it directly.
 */
@Composable
private fun TestDirections(ui: LinkUiController) {
    val s = BleService.instance
    val all = remember { CodeTest.steps() }
    val index = s?.codeTestIndex ?: -1
    val steps = if (index >= 0) s?.codeTestSteps ?: all else all
    val step = steps.getOrNull(index)
    var saved by remember { mutableStateOf(s?.pictoOverrides() ?: emptyMap()) }
    RaceCard {
        SectionTitle("Test directions", "Parked, ignition on. Start recording a video of the cluster, then tap Start. " +
            "Every code plays for 2 s with its number on the dash (${steps.size} codes, about ${CodeTest.durationS() / 60 + 1} min). Share the video here.")
        if (step != null) {
            Text(step.label, style = MaterialTheme.typography.displaySmall)
            LinearProgressIndicator(progress = { (index + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
            Muted("${index + 1} of ${steps.size} · ${(steps.size - index) * CodeTest.STEP_MS / 1000} s left")
        }
        // resume a step early so the new video overlaps the old one
        val resumeAt = if (s?.codeTestRunning == true) -1 else (s?.codeTestResumeAt() ?: -1).let { if (it > 0) (it - 1).coerceAtLeast(0) else -1 }
        if (resumeAt >= 0) Muted("Last test stopped at ${steps.getOrNull(resumeAt + 1)?.label ?: steps[resumeAt].label}" +
            (s?.codeTestStopReason?.let { " ($it)" } ?: "") + ".")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s?.codeTestRunning == true) Button(onClick = hapticAction({ s.stopCodeTest(); ui.refresh() }), modifier = Modifier.weight(1f)) { Text("Stop") }
            else {
                if (resumeAt >= 0) Button(onClick = hapticAction({ ui.notice = s!!.startCodeTest(resumeAt); ui.refresh() }), enabled = ui.state.connected,
                    modifier = Modifier.weight(1f)) { Text("Resume at ${steps[resumeAt].label}", maxLines = 1) }
                val start = @Composable { Text(if (resumeAt >= 0) "From start" else "Start test") }
                if (resumeAt >= 0) OutlinedButton(onClick = hapticAction({ s?.let { ui.notice = it.startCodeTest(); ui.refresh() } }), enabled = ui.state.connected) { start() }
                else Button(onClick = hapticAction({ s?.let { ui.notice = it.startCodeTest(); ui.refresh() } }), enabled = ui.state.connected, modifier = Modifier.weight(1f)) { start() }
            }
        }
        if (s?.codeTestRunning != true) {
            OutlinedButton(onClick = hapticAction({ s?.let { ui.notice = it.startCodeTest(distance = true); ui.refresh() } }), enabled = ui.state.connected,
                modifier = Modifier.fillMaxWidth()) { Text("Distance test (1 min)") }
            Muted("The dash shows 70 m as \"0 m\". This tries 70 m in every format, then 5 to 150 m. Film it and share.")
        }
        if (step?.kind == CodeTest.Kind.ARROW) {
            Text("Arrow ${step.value} is…", style = MaterialTheme.typography.titleMedium)
            listOf(Maneuver.LEFT, Maneuver.STRAIGHT, Maneuver.UTURN_LEFT, Maneuver.UTURN_RIGHT, Maneuver.SLIGHT_LEFT, Maneuver.SLIGHT_RIGHT,
                Maneuver.SHARP_LEFT, Maneuver.SHARP_RIGHT, Maneuver.ROUNDABOUT, Maneuver.KEEP_LEFT, Maneuver.KEEP_RIGHT, Maneuver.DESTINATION).chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { m ->
                        OutlinedButton(onClick = hapticAction({
                            s?.savePicto(m, step.value); saved = s?.pictoOverrides() ?: saved
                            ui.notice = "Saved: ${m.name.lowercase().replace('_', ' ')} = arrow ${step.value}"
                        }), modifier = Modifier.weight(1f)) {
                            Text(m.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + (saved[m]?.let { " · $it" } ?: ""), maxLines = 1)
                        }
                    }
                }
            }
        }
        Muted("Or just send the video: every step is also logged with its time, so it can be matched to the recording.")
    }
}

@Composable
private fun DevArrowTest(ui: LinkUiController) {
    var arrow by rememberSaveable { mutableStateOf("3") }
    var maneuver by rememberSaveable { mutableStateOf(Maneuver.RIGHT.name) }
    var maneuverMenu by remember { mutableStateOf(false) }
    val s = BleService.instance
    RaceCard {
        SectionTitle("Arrow test", "Show a code, inspect the cluster, then save what you saw. Right = 3 is confirmed.")
        OutlinedTextField(arrow, { arrow = it.filter(Char::isDigit).take(3) }, label = { Text("Arrow code · 0–127") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = hapticAction({ arrow = ((arrow.toIntOrNull() ?: 1) - 1).coerceAtLeast(0).toString(); ui.arrow(arrow.toInt()) }),
                modifier = Modifier.semantics { contentDescription = "Previous arrow code" }) { Text("−") }
            Button(onClick = hapticAction({ ui.arrow(arrow.toInt()) }), enabled = arrow.toIntOrNull() in 0..127, modifier = Modifier.weight(1f)) { Text("Show code ${arrow.ifBlank { "?" }}") }
            OutlinedButton(onClick = hapticAction({ arrow = ((arrow.toIntOrNull() ?: -1) + 1).coerceAtMost(127).toString(); ui.arrow(arrow.toInt()) }),
                modifier = Modifier.semantics { contentDescription = "Next arrow code" }) { Text("+") }
        }
        Box {
            OutlinedButton(onClick = hapticAction({ maneuverMenu = true })) { Text("Cluster showed: ${maneuver.lowercase().replace('_', ' ')}") }
            DropdownMenu(maneuverMenu, { maneuverMenu = false }) {
                Maneuver.entries.filter { it != Maneuver.UNKNOWN }.forEach { m ->
                    DropdownMenuItem(text = { Text(m.name.lowercase().replace('_', ' ')) }, onClick = hapticAction({ maneuver = m.name; maneuverMenu = false }))
                }
            }
        }
        OutlinedButton(onClick = hapticAction({ ui.bikeCommand { it.savePicto(Maneuver.valueOf(maneuver), arrow.toInt()); "Arrow mapping saved." } }),
            enabled = arrow.toIntOrNull() in 0..127) { Text("Save arrow mapping") }
        Muted(s?.pictoOverrides()?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }?.ifBlank { "No custom mappings." } ?: "Connect to inspect mappings.")
    }
}

/** What each known frame carries (1-based byte positions, see PROTOCOL.md). */
private val FRAME_HINTS = mapOf(
    "5A10" to "b3 speed · b4–6 odometer · b7 fuel · b10 top · b11 throttle · b12 switch held (3 MODE, 4 i/Info, 128 engine stopped) · b17–18 RPM",
    "5A11" to "b6 gear (low nibble) · b7 bit 0 right-cube switch? · b11 bit 7 + b14 bit 5 pop-up state · b18 riding mode (low nibble)",
    "5A18" to "b9 engine temp · b12 battery ÷10",
    "5A19" to "b7–10 trip A · b14–15 average speed")

private fun LazyListScope.devData(ui: LinkUiController) {
    val frames = ui.state.decoded.toSortedMap()
    item { Muted("Live decoded frames. Bytes that changed in the last 3 s are highlighted — flick a switch and watch.") }
    if (frames.isEmpty()) item { RaceCard { Muted("No decoded frames. Connect to your bike first.") } }
    frames.forEach { (id, hex) -> item(key = id) { FrameCard(id, hex) } }
    item {
        RaceCard {
            SectionTitle("Switch changes")
            Muted(ui.state.buttons.joinToString("\n").ifBlank { "No switch changes yet." })
        }
    }
}

@Composable
private fun FrameCard(id: String, hex: String) {
    val bytes = hex.chunked(2)
    val previous = remember(id) { arrayOfNulls<String>(Protocol.FRAME_LEN) }
    val changedAt = remember(id) { LongArray(Protocol.FRAME_LEN) }
    val now = System.currentTimeMillis()
    bytes.forEachIndexed { i, b -> if (i < previous.size) { if (previous[i] != null && previous[i] != b) changedAt[i] = now; previous[i] = b } }
    RaceCard {
        SectionTitle(id, FRAME_HINTS[id])
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bytes.chunked(10).forEachIndexed { row, chunk ->
                    Row(Modifier.fillMaxWidth()) {
                        chunk.forEachIndexed { col, b ->
                            val i = row * 10 + col
                            val hot = i < changedAt.size && now - changedAt[i] < 3000
                            Column(Modifier.weight(1f).background(if (hot) MaterialTheme.colorScheme.primary.copy(alpha = .25f) else Color.Transparent,
                                RoundedCornerShape(6.dp)).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(b.uppercase(), fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                    fontWeight = if (hot) FontWeight.Bold else FontWeight.Normal)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.devLog(ui: LinkUiController) {
    item { DevMarkers(ui) }
    item {
        RaceCard {
            SectionTitle("Raw log tail", "Latest 40 events")
            SelectionContainer { Text(ui.state.tail.joinToString("\n").ifBlank { "No events yet." }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DevMarkers(ui: LinkUiController) {
    var note by rememberSaveable { mutableStateOf("") }
    RaceCard {
        SectionTitle("Markers", "Tag what you just did so it can be found in the log.")
        OutlinedTextField(note, { note = it }, label = { Text("Optional marker note") }, modifier = Modifier.fillMaxWidth())
        listOf("Info: single press", "Info: double press", "Info: long press", "Ignition ON", "Ignition OFF", "Idle in neutral",
            "Rev about 4000", "Gear 1", "Gear 2", "Throttle open", "Throttle closed", "Left indicator", "Right indicator",
            "Riding mode changed", "Cluster reading", "Something odd").chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { label ->
                    OutlinedButton(onClick = hapticAction({ ui.bikeCommand { it.emit("ui", "marker", "label" to label, "note" to note); "Marker saved." }; note = "" }),
                        modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DeveloperToggle(label: String, initial: Boolean, update: (Boolean) -> Unit) {
    var checked by remember(initial) { mutableStateOf(initial) }
    SettingSwitch(label, "", checked) { checked = it; update(it) }
}
