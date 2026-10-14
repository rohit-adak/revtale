package io.github.revtale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The cockpit lies under the drawer's see-through top, and that space gets the touches. Buttons in the cockpit
 * register here ([cockpitTap]) and the see-through space hands taps on to them.
 */
class CockpitTaps {
    val bounds = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()
    val actions = mutableMapOf<String, () -> Unit>()
    /** The smallest target under [p] wins, so a close button inside a tappable card still works. */
    fun tap(p: Offset): Boolean {
        val key = bounds.filter { it.value.contains(p) }.minByOrNull { it.value.width * it.value.height }?.key ?: return false
        actions[key]?.invoke(); return true
    }
}
val LocalCockpitTaps = staticCompositionLocalOf<CockpitTaps?> { null }

@Composable
fun Modifier.cockpitTap(key: String, action: () -> Unit): Modifier {
    val taps = LocalCockpitTaps.current ?: return this
    SideEffect { taps.actions[key] = action }
    DisposableEffect(key) { onDispose { taps.actions.remove(key); taps.bounds.remove(key) } }
    return onGloballyPositioned { taps.bounds[key] = it.boundsInRoot() }
}

@Composable
fun LiveScreen(state: LinkSnapshot, onConnect: () -> Unit, onRides: () -> Unit, extras: LazyListScope.() -> Unit = {}, drawerPull: Int = 0,
               notCalibrated: Boolean = false, onCalibrate: () -> Unit = {}, calibrationCard: Boolean = false, onCalibrateLater: () -> Unit = {}) {
    // The cockpit (name, vitals, gauge) stays put behind; everything else is a drawer that slides up over it as you
    // scroll, while the cockpit dims and shrinks a little underneath.
    val list = rememberLazyListState()
    // a tap on the parked / away notification or the widget: pull the drawer up
    LaunchedEffect(drawerPull) { if (drawerPull > 0) list.animateScrollToItem(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topH by remember { mutableIntStateOf(0) }
    var boxH by remember { mutableIntStateOf(0) }
    val peek = with(density) { 28.dp.toPx() }
    // at rest the drawer shows its handle and its first card (Connect) fully above the floating capsule; the capsule's
    // top is ~22 dp below where the bar's area starts. Anything further down fades out before reaching the capsule.
    val capsuleTop = with(density) { (LocalBottomInset.current - 22.dp).toPx() }
    val firstCard = with(density) { (24.dp + 54.dp + 16.dp).toPx() }
    val fade = with(density) { 14.dp.toPx() }
    val rest = if (boxH > 0) minOf(topH - peek, boxH - capsuleTop - firstCard) else topH - peek
    val sheetTop by remember(rest) {
        derivedStateOf {
            list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 1 }?.offset?.toFloat()
                ?: if (list.firstVisibleItemIndex > 1) 0f else rest
        }
    }
    val progress = if (topH > 0 && rest > 0f) (1f - sheetTop / rest).coerceIn(0f, 1f) else 0f
    val sheet = MaterialTheme.colorScheme.surface
    val handle = MaterialTheme.colorScheme.outlineVariant
    // at rest the drawer's content stops just above the capsule; pulled up, it runs under the bar as usual
    val reveal = (progress * 6f).coerceIn(0f, 1f)
    val taps = remember { CockpitTaps() }
    CompositionLocalProvider(LocalCockpitTaps provides taps) {
    Box(Modifier.fillMaxSize().onSizeChanged { boxH = it.height }) {
        Column(Modifier.fillMaxWidth().onSizeChanged { topH = it.height }
            .graphicsLayer { alpha = 1f - 0.55f * progress; val k = 1f - 0.06f * progress; scaleX = k; scaleY = k }
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LiveHeader(state, notCalibrated && !state.live.moving, onCalibrate, calibrationCard, onCalibrateLater)
            InstrumentStrip(state)
            LiveGauge(state.live)
        }
        Box(Modifier.fillMaxSize().drawBehind {
            // the drawer: rounded top edge with a grab handle, solid down to the bottom
            val r = 28.dp.toPx()
            drawRoundRect(sheet, Offset(0f, sheetTop), Size(size.width, size.height - sheetTop + r), androidx.compose.ui.geometry.CornerRadius(r, r))
            drawRoundRect(handle, Offset(size.width / 2 - 18.dp.toPx(), sheetTop + 10.dp.toPx()), Size(36.dp.toPx(), 4.dp.toPx()),
                androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        })
        LazyColumn(Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val edge = (size.height - capsuleTop + fade / 7) + (capsuleTop + fade) * reveal
                drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0f to androidx.compose.ui.graphics.Color.Black,
                    (edge - fade).coerceAtLeast(0f) / size.height to androidx.compose.ui.graphics.Color.Black,
                    (edge / size.height).coerceAtMost(1f) to androidx.compose.ui.graphics.Color.Transparent,
                    1f to androidx.compose.ui.graphics.Color.Transparent),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
            }, state = list, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp + LocalBottomInset.current), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // index 0: see-through space over the cockpit; index 1 marks the drawer's top edge
        item {
            var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            Spacer(Modifier.fillMaxWidth().height(with(density) { rest.coerceAtLeast(0f).toDp() }.minus(20.dp).coerceAtLeast(0.dp))
                .onGloballyPositioned { coords = it }
                .pointerInput(Unit) { detectTapGestures { off -> coords?.let { taps.tap(it.localToRoot(off)) } } })
        }
        item { Spacer(Modifier.height(4.dp)) }
        // not connected: the way to connect is the first thing on the drawer, visible above the bar at rest
        if (!state.live.moving && !state.connected) {
            item { ConnectButton(state, onConnect) }
            item { Muted("Turn the ignition on and keep your phone near the bike. Live values appear once the cluster reports them.") }
        }
        state.navMinutes?.let { mins -> item { RouteNowCard(state.navDest, mins, state.navLeftM) } }
        state.heading?.let { heading -> item { CompassCard(heading) } }
        state.ride?.let { ride ->
            item {
                RaceCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("This ride · live")
                        Icon(Icons.Outlined.FiberManualRecord, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) { Text("${decimal(ride.distanceKm)} km", style = MaterialTheme.typography.titleLarge); Muted("Distance") }
                        Column(Modifier.weight(1f)) { Text(duration(ride.elapsedS.toDouble()), style = MaterialTheme.typography.titleLarge); Muted("Ride time") }
                        Column(Modifier.weight(1f)) { Text("${ride.topSpeed}", style = MaterialTheme.typography.titleLarge); Muted("Top km/h") }
                    }
                    if (!state.live.moving) TextButton(onClick = hapticAction(onRides), contentPadding = PaddingValues(0.dp)) {
                        Text("Open live analysis"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null)
                    }
                }
            }
        }
        state.live.trip?.let { trip -> item { Muted("Trip A on the dash: ${decimal(trip)} km") } }
        if (state.live.moving) {
            item { Muted("Ride focus is on. Controls return when you stop.") }
        } else {
            if (state.connected) item { ConnectButton(state, onConnect) }
            extras()
        }
    }
    }
    }
}

@Composable
private fun ConnectButton(state: LinkSnapshot, onConnect: () -> Unit) {
    Button(onClick = hapticAction(onConnect), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
        Icon(if (state.connected) Icons.Outlined.BluetoothDisabled else Icons.Outlined.Bluetooth, null)
        Spacer(Modifier.width(8.dp))
        Text(if (state.bikeAddress == null) "Find my bike" else if (state.connected) "Disconnect" else "Connect to bike")
    }
}

/** Direction of travel from GPS. Static drawing (no animation), refreshed with the rest of the live data. */
@Composable
private fun CompassCard(heading: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val sector = Compass.sector(heading)
    RaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.clearAndSetSemantics { contentDescription = "Heading ${Compass.words(sector).lowercase()}, ${heading.toInt()} degrees" }) {
            Canvas(Modifier.size(72.dp)) {
                val r = size.minDimension / 2
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(ring, r - 2.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                repeat(8) { i ->
                    val a = Math.toRadians(i * 45.0 - 90)
                    val len = if (i % 2 == 0) 8.dp.toPx() else 4.dp.toPx()
                    drawLine(ring, Offset(c.x + cos(a).toFloat() * (r - 2.dp.toPx()), c.y + sin(a).toFloat() * (r - 2.dp.toPx())),
                        Offset(c.x + cos(a).toFloat() * (r - len), c.y + sin(a).toFloat() * (r - len)), 2.dp.toPx())
                }
                val a = Math.toRadians(heading - 90.0)
                val tip = Offset(c.x + cos(a).toFloat() * (r - 10.dp.toPx()), c.y + sin(a).toFloat() * (r - 10.dp.toPx()))
                val tail = Offset(c.x - cos(a).toFloat() * (r - 22.dp.toPx()), c.y - sin(a).toFloat() * (r - 22.dp.toPx()))
                drawLine(onSurface, tail, c, 4.dp.toPx(), StrokeCap.Round)
                drawLine(primary, c, tip, 5.dp.toPx(), StrokeCap.Round)
                drawCircle(primary, 4.dp.toPx(), c)
            }
            Column {
                Eyebrow("Heading")
                Text(Compass.words(sector).lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Muted("${heading.toInt()}° · from GPS while moving")
            }
        }
    }
}

@Composable
private fun LiveGauge(live: LiveTelemetry) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val rpm = ((live.rpm ?: 0) / 12000f).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(300.dp).clearAndSetSemantics {
        contentDescription = "Speed ${live.speed?.let { "$it kilometres per hour" } ?: "unavailable"}. " +
            "Engine ${live.rpm?.let { "$it RPM" } ?: "unavailable"}. Gear ${live.gear?.let { if (it == 0) "neutral" else "$it" } ?: "unavailable"}."
    }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = minOf(size.width, size.height) - 24.dp.toPx()
            val topLeft = Offset((size.width - diameter) / 2, 10.dp.toPx())
            val center = topLeft + Offset(diameter / 2, diameter / 2)
            drawArc(track, 140f, 260f, false, topLeft, Size(diameter, diameter), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            if (rpm > 0f) drawArc(primary, 140f, 260f * rpm, false, topLeft, Size(diameter, diameter), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
            repeat(25) { i ->
                val angle = Math.toRadians((140 + 260 * i / 24.0))
                val radius = diameter / 2 - 14.dp.toPx()
                val length = if (i % 2 == 0) 8.dp.toPx() else 4.dp.toPx()
                drawLine(if (i / 24f <= rpm && live.rpm != null) primary else track,
                    Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius),
                    Offset(center.x + cos(angle).toFloat() * (radius - length), center.y + sin(angle).toFloat() * (radius - length)), 2.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(live.speed?.toString() ?: "—", style = MaterialTheme.typography.displayLarge)
            Text("km/h", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(live.rpm?.toString() ?: "—", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    Muted("RPM")
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(live.gear?.let { if (it == 0) "N" else "$it" } ?: "—", Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                        fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Text("0", Modifier.align(Alignment.BottomStart).padding(start = 30.dp, bottom = 30.dp), style = MaterialTheme.typography.labelSmall)
        Text("12K RPM", Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 30.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SetupScreen(ui: LinkUiController, onDone: () -> Unit) {
    var candidate by remember { mutableStateOf<BleService.Dev?>(null) }
    var allDevices by remember { mutableStateOf(false) }
    val state = ui.state
    val nearby = state.devices.filter { System.currentTimeMillis() - it.seen < 15000 }
        .filter { allDevices || Bikes.looksLikeBike(it.name) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = barPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { PageHeading("Bike setup", "Find your bike", "Turn on the ignition, then choose your bike from the nearby devices.") }
        item {
            RaceCard {
                Icon(Icons.Outlined.SportsMotorsports, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                SectionTitle("Ignition on. Phone nearby.")
                Muted("Turn on the bike's ignition and Bluetooth. Match its cluster name to a result below before connecting.")
                if (!ui.finding) Button(onClick = hapticAction({ ui.requestStart(StartAction.FIND) }), enabled = !ui.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (ui.busy) "Starting…" else "Find my bike") }
                else {
                    StatusBadge(if (state.scanning) "Looking for nearby bikes" else "Scan paused", state.scanning)
                    OutlinedButton(onClick = hapticAction({ ui.requestStart(StartAction.FIND) })) { Text("Scan again") }
                }
            }
        }
        if (ui.finding) {
            item { SectionTitle("Nearby bikes", "Sorted by signal strength. Select only a bike you own.") }
            if (nearby.isEmpty()) item { RaceCard { Muted("No bikes found yet. Check the ignition and Bluetooth, then scan again.") } }
            items(nearby, key = { it.addr }) { device ->
                OutlinedCard(onClick = hapticAction({ candidate = device }), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(device.name ?: "Unnamed device", style = MaterialTheme.typography.titleMedium)
                            Muted(device.addr)
                        }
                        Text("${device.rssi}\ndBm", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
                    }
                }
            }
            item { SettingSwitch("Show all Bluetooth devices", "Use this if your bike advertises a different name.", allDevices) { allDevices = it } }
        }
        item { RaceCard { Eyebrow("02 / Pair securely"); Muted("Android may ask for a passkey. Enter the code shown on your bike's cluster. RevTale never selects a bike for you.") } }
        item { TextButton(onClick = hapticAction({ ui.cancelFinding(); onDone() })) { Text(if (state.bikeAddress == null) "Explore the app first" else "Back to Live") } }
    }
    candidate?.let { device ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("Is this your bike?") },
            text = { Text("${device.name ?: "Unnamed device"}\n${device.addr}\n\nConfirm that this name matches your cluster. If Android asks, enter the passkey shown on the bike.") },
            confirmButton = { TextButton(onClick = hapticAction({ ui.chooseBike(device); candidate = null; onDone() })) { Text("Yes, connect") } },
            dismissButton = { TextButton(onClick = hapticAction({ candidate = null })) { Text("Cancel") } })
    }
}

/** The route running in Google Maps, from its own estimate (live traffic). Static text, refreshed with the live data. */
@Composable
private fun RouteNowCard(dest: String?, minutes: Int, leftM: Int?) {
    val at = java.time.LocalTime.now().plusMinutes(minutes.toLong())
    RaceCard {
        Eyebrow(dest?.let { "To $it" } ?: "Route")
        Text("Arrive " + at.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.getDefault())),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Muted(listOfNotNull("$minutes min left", leftM?.let { "${decimal(it / 1000.0)} km" }, "Google Maps, live traffic").joinToString(" · "))
    }
}

/** Bike name with its state on one line: connected / waiting / off, the riding mode, and when it was last seen. */
@Composable
private fun LiveHeader(state: LinkSnapshot, notCalibrated: Boolean = false, onCalibrate: () -> Unit = {},
                       calibrationCard: Boolean = false, onCalibrateLater: () -> Unit = {}) {
    val on = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when { state.simulating -> "Emulator bike"; state.bikeAddress == null -> "No bike yet"; else -> state.bikeName }, style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (state.connected) on else MaterialTheme.colorScheme.outline, RoundedCornerShape(50)))
            val status = when {
                state.live.moving -> "Riding"
                state.connected -> "Connected"
                state.waiting -> "Waiting for ignition"
                else -> "Off"
            }
            // when it was last seen is on the instrument strip ("LAST KNOWN · 2 HOURS AGO")
            val extra = listOfNotNull(state.live.mode?.takeIf { state.connected }?.let { RideModes.name(it) + " mode" })
            Text((listOf(status) + extra).joinToString("  ·  "), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // always visible until done: the defaults may not match this bike. First a card that says why; put off, a tag
        if (notCalibrated && calibrationCard) CalibrationNudge(onCalibrate, onCalibrateLater)
        else if (notCalibrated) AssistChip(onClick = hapticAction(onCalibrate), modifier = Modifier.cockpitTap("calibrate", hapticAction(onCalibrate)), label = { Text("Not calibrated · tap to calibrate") },
            leadingIcon = { Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp)) },
            colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                labelColor = MaterialTheme.colorScheme.onErrorContainer, leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer),
            border = null)
    }
}

/**
 * The bike's vitals in one instrument strip: fuel (with its bars), odometer, battery and engine — live when connected,
 * else the last reading, dimmed. Same place, same size every time, so the eye always finds them.
 */
@Composable
private fun InstrumentStrip(state: LinkSnapshot) {
    val live = state.live.takeIf { state.connected }
    val last = state.lastKnown
    val fuel = live?.fuel ?: last?.fuel
    val fuelMax = Calibration.fuelMax(androidx.compose.ui.platform.LocalContext.current, fuel)
    val odo = live?.odometer ?: last?.odometer
    val volts = live?.volts ?: last?.volts
    val temp = live?.temperature
    val stale = live == null
    val accent = MaterialTheme.colorScheme.primary
    val value = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))) {
        Column {
        // not live: say so on the strip itself, with when the bike last reported
        if (stale && last != null) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f))
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.History, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("LAST KNOWN · " + android.text.format.DateUtils.getRelativeTimeSpanString(last.atMs, System.currentTimeMillis(),
                android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 1.sp)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 12.dp)) {
            @Composable
            fun Cell(label: String, text: String, note: String, alert: Boolean = false, below: (@Composable () -> Unit)? = null) {
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp, maxLines = 1)
                    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (alert) accent else value)
                    if (below != null) below() else Text(note, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        color = if (alert) accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            @Composable
            fun Divider() = Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)))
            Cell("Fuel", fuel?.let { "$it/$fuelMax" } ?: "—", "", alert = fuel != null && fuel <= 1) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(fuelMax) { i ->
                        Box(Modifier.weight(1f).height(4.dp).background(
                            if (fuel != null && i < fuel) (if (fuel <= 1) accent else value) else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(2.dp)))
                    }
                }
            }
            Divider()
            Cell("Odo", odo?.let { "%,d".format(it.toInt()) } ?: "—", "km")
            Divider()
            Cell("Batt", volts?.let { "${decimal(it)} V" } ?: "—", when {
                volts == null -> ""; volts < 11.8 -> "low"; !stale && volts >= 13.2 -> "charging"; else -> "ok"
            }, alert = volts != null && volts < 11.8)
            Divider()
            Cell("Engine", temp?.let { "$it °C" } ?: "—", when {
                temp == null -> if (stale) "when on" else ""; temp >= Heat.VERY_HOT -> "very hot"; temp >= Heat.HOT -> "hot"; temp < 60 -> "cold"; else -> "normal"
            }, alert = temp != null && temp >= Heat.HOT)
        }
        }
    }
}

/** Fuel, odometer, battery (and engine heat while on) as pills: live when connected, else the bike's last report. */
@Composable
private fun ValuePills(state: LinkSnapshot) {
    val live = state.live.takeIf { state.connected }
    val last = state.lastKnown
    val fuel = live?.fuel ?: last?.fuel
    val fuelMax = Calibration.fuelMax(androidx.compose.ui.platform.LocalContext.current, fuel)
    val odo = live?.odometer ?: last?.odometer
    val volts = live?.volts ?: last?.volts
    // always shown (dashes until the bike has reported once), so the top of the Live tab never jumps
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(Icons.Outlined.LocalGasStation, fuel?.let { "$it/$fuelMax" } ?: "—", alert = fuel != null && fuel <= 1)
            Pill(Icons.Outlined.Route, odo?.let { "%,d km".format(it.toInt()) } ?: "— km")
            Pill(Icons.Outlined.BatteryChargingFull, volts?.let { "${decimal(it)} V" } ?: "— V", alert = volts != null && volts < 11.8)
            live?.temperature?.let { Pill(Icons.Outlined.Thermostat, "$it °C", alert = it >= Heat.HOT) }
        }
        if (live == null && last != null) Muted("Last seen " +
            android.text.format.DateUtils.getRelativeTimeSpanString(last.atMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS))
    }
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, alert: Boolean = false) {
    Surface(shape = RoundedCornerShape(50), color = if (alert) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(13.dp), tint = if (alert) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                color = if (alert) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
