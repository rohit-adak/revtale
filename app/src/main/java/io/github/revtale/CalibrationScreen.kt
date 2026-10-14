package io.github.revtale

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calibrate the bike like a universal remote: press each switch, say what the dash shows. Parked only; the bike only
 * gets display frames (a test line and test arrows). Everything at once, or just one thing again (only the fuel
 * gauge, only the i switch); what isn't redone keeps its saved value.
 */
private sealed class CalStep(val title: String) {
    class Switch(val id: String, title: String, val how: String) : CalStep(title)
    object Gears : CalStep("Gears")
    object Fuel : CalStep("Fuel gauge")
    object Name : CalStep("Your name on the dash")
    object Line : CalStep("Message line")
    object Arrows : CalStep("Navigation arrows")
}

private val ITEMS = listOf(
    CalStep.Switch(BikeCalibration.INFO, "i switch", "Press and hold the i switch for about a second, then let go."),
    CalStep.Switch(BikeCalibration.MODE, "Mode switch", "Press and hold MODE for about a second, then let go. The riding mode may change; set it back after."),
    CalStep.Switch(BikeCalibration.LEFT, "Left indicator", "Switch the left indicator on for two blinks, then off."),
    CalStep.Switch(BikeCalibration.RIGHT, "Right indicator", "Switch the right indicator on for two blinks, then off."),
    CalStep.Switch(BikeCalibration.HIGH_BEAM, "High beam", "Switch to high beam for a second, then back."),
    CalStep.Gears, CalStep.Fuel, CalStep.Name, CalStep.Line, CalStep.Arrows)

private val ARROWS: List<Triple<Maneuver, String, ImageVector>> = listOf(
    Triple(Maneuver.LEFT, "Turn left", Icons.Outlined.TurnLeft),
    Triple(Maneuver.RIGHT, "Turn right", Icons.Outlined.TurnRight),
    Triple(Maneuver.STRAIGHT, "Straight on", Icons.Outlined.Straight),
    Triple(Maneuver.UTURN_LEFT, "U-turn", Icons.Outlined.UTurnLeft),
    Triple(Maneuver.ROUNDABOUT, "Roundabout", Icons.Outlined.RoundaboutRight),
    Triple(Maneuver.DESTINATION, "Destination flag", Icons.Outlined.Flag))

/**
 * Other arrows the dash can draw for each turn (codes filmed on the RTR 160 4V, see [Pictograms]), tried in order
 * when the rider says the default looks wrong. Another model may number its arrows differently.
 */
private val ARROW_LOOKS: Map<Maneuver, List<Int>> = mapOf(
    Maneuver.LEFT to listOf(0, 2, 1, 19, 11, 13, 22, 10, 15),
    Maneuver.RIGHT to listOf(3, 5, 4, 20, 12, 14, 24, 9, 16),
    Maneuver.STRAIGHT to listOf(7, 50, 21),
    Maneuver.UTURN_LEFT to listOf(6, 41, 88),
    Maneuver.ROUNDABOUT to listOf(71, 70, 86, 87, 21, 88),
    Maneuver.DESTINATION to listOf(8))

private const val TEST_LINE = "CALIBRATION 7"

@Composable
fun CalibrationScreen(ui: LinkUiController, onDone: () -> Unit) {
    // start from what's saved, so redoing one thing keeps the rest
    val saved = remember { Calibration.load(ui.context) }
    val switches = remember { mutableStateMapOf<String, SwitchSignal>().apply { saved?.switches?.let(::putAll) } }
    var fuelMax by remember { mutableStateOf(saved?.fuelMax) }
    var nameShown by remember { mutableStateOf(saved?.nameShown) }
    var lineShown by remember { mutableStateOf(saved?.lineShown) }
    val arrows = remember { mutableStateMapOf<String, Boolean>().apply { saved?.arrows?.let(::putAll) } }
    val gears = remember { mutableStateMapOf<Int, Int>().apply { saved?.gears?.let(::putAll) } }
    val arrowCodes = remember { mutableStateMapOf<String, Int>().apply { saved?.arrowCodes?.let(::putAll) } }
    // null = the list; else the items being (re)done, in order, and where we are (past the end = review and save)
    var queue by remember { mutableStateOf<List<Int>?>(null) }
    var pos by remember { mutableIntStateOf(0) }
    var changed by remember { mutableStateOf(false) }
    val connected = ui.state.connected
    fun next() { pos++ }
    fun status(item: CalStep): String = when (item) {
        is CalStep.Switch -> switches[item.id]?.let(::describe) ?: if (item.id == BikeCalibration.INFO) "default (5A10 b12 = 4)" else "not set"
        CalStep.Gears -> if (gears.isEmpty()) "default (N, 1–6)" else gears.entries.sortedBy { it.value }
            .joinToString(" ") { (raw, g) -> "${if (g == 0) "N" else g}=$raw" }
        CalStep.Fuel -> fuelMax?.let { "$it bars when full" } ?: "default (${Calibration.DEFAULT_FUEL_MAX})"
        CalStep.Name -> yesNo(nameShown)
        CalStep.Line -> yesNo(lineShown)
        CalStep.Arrows -> if (arrows.isEmpty()) "not checked" else listOfNotNull("${arrows.count { it.value }} of ${arrows.size} right",
            ARROWS.filter { (m) -> m.name in arrowCodes }.map { it.second.lowercase() }.takeIf { it.isNotEmpty() }?.let { "another look for " + it.joinToString() },
            ARROWS.filter { (m) -> arrows[m.name] == false && m.name !in arrowCodes }.map { it.second.lowercase() }.takeIf { it.isNotEmpty() }
                ?.let { "as text: " + it.joinToString() }).joinToString(" · ")
    }
    fun start(items: List<Int>) {
        // a switch being redone listens afresh
        items.map { ITEMS[it] }.filterIsInstance<CalStep.Switch>().forEach { switches.remove(it.id) }
        queue = items; pos = 0; changed = true
    }
    val q = queue
    val step = q?.getOrNull(pos)?.let { ITEMS[it] }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(barPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = hapticAction({ ui.stopDemo(); if (q != null) queue = null else onDone() }), contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text(if (q != null) "All checks" else "Settings")
        }
        when {
            // the list: everything, or one thing again
            q == null -> {
                PageHeading("Calibrate", if (saved == null) "Teach RevTale your bike" else "Your bike's calibration")
                RaceCard {
                    Muted("Every model reports its switches and draws its dash a little differently. Press a switch, tell RevTale " +
                        "what you see, like setting up a TV remote. Parked, ignition on; the bike only gets a test message and test arrows.")
                    Button(onClick = hapticAction({ start(ITEMS.indices.toList()) }), Modifier.fillMaxWidth()) {
                        Text(if (saved == null) "Calibrate everything" else "Redo everything")
                    }
                }
                RaceCard {
                    Text("Or just one thing", style = MaterialTheme.typography.titleMedium)
                    ITEMS.forEachIndexed { i, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                Muted(status(item))
                            }
                            TextButton(onClick = hapticAction({ start(listOf(i)) })) { Text("Calibrate") }
                        }
                    }
                }
                if (changed) Button(onClick = hapticAction({ queue = emptyList(); pos = 0 }), Modifier.fillMaxWidth()) { Text("Review and save") }
            }
            // one step of the run
            step != null -> {
                PageHeading(if (q.size > 1) "Calibrate · ${pos + 1} of ${q.size}" else "Calibrate", step.title)
                if (q.size > 1) LinearProgressIndicator({ (pos + 1f) / q.size }, Modifier.fillMaxWidth())
                if (!connected) RaceCard {
                    Text("Connect to your bike", style = MaterialTheme.typography.titleMedium)
                    Muted("Ignition on, engine off, bike on its stand. The steps continue once it is connected.")
                }
                when (step) {
                    is CalStep.Switch -> SwitchStep(step, connected, switches[step.id], { switches[step.id] = it }, { switches.remove(step.id) }, ::next)
                    CalStep.Gears -> GearStep(connected, gears, ::next)
                    CalStep.Fuel -> FuelStep(connected) { fuelMax = it; next() }
                    CalStep.Name -> YesNoStep("Do you see your name, ${ui.prefs.getString("name", null)?.uppercase() ?: "RIDER"}, on the dash?",
                        "It is sent when the phone links. Not shown? Some models have no name area.") { nameShown = it; next() }
                    CalStep.Line -> {
                        LaunchedEffect(Unit) { ui.bikeCommand { it.sendTextSlot(Protocol.ID_CUSTOM_LINE, TEST_LINE) } }
                        YesNoStep("Do you see $TEST_LINE on the dash?", "This line carries road names, the text-back prompt and ride summaries.") {
                            lineShown = it; next()
                        }
                        TextButton(onClick = hapticAction({ ui.bikeCommand { it.sendTextSlot(Protocol.ID_CUSTOM_LINE, TEST_LINE) } })) { Text("Send it again") }
                    }
                    CalStep.Arrows -> ArrowStep(ui, arrows, arrowCodes) { ui.stopDemo(); next() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = hapticAction({ if (pos > 0) pos-- else queue = null })) { Text("Back") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = hapticAction(::next)) { Text("Skip") }
                }
            }
            // done: review and save (what wasn't redone keeps its saved value)
            else -> {
                val result = BikeCalibration(System.currentTimeMillis(), BleService.instance?.bikeName() ?: "bike",
                    switches.toMap(), fuelMax, nameShown, lineShown, arrows.toMap(), gears.toMap(), arrowCodes.toMap())
                PageHeading("Calibrate", "All set")
                RaceCard { ITEMS.forEach { SummaryRow(it.title, status(it)) } }
                Button(onClick = hapticAction({
                    Calibration.save(ui.context, result); BleService.instance?.reloadCalibration(); ui.calibrationSaved()
                    ui.notice = "Calibration saved"; onDone()
                }), Modifier.fillMaxWidth()) { Text("Save calibration") }
                OutlinedButton(onClick = hapticAction({ queue = null }), Modifier.fillMaxWidth()) { Text("Calibrate something else") }
                OutlinedButton(onClick = hapticAction({ ui.shareCalibration(result) }), Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(8.dp)); Text("Share this bike's profile")
                }
                Muted("Sharing helps support more models. It holds the switch readings and your answers, not your location or the bike's Bluetooth address.")
            }
        }
    }
}

private fun describe(s: SwitchSignal) = "${s.key.replace(".", " ")} · bits %02X".format(s.bits)
private fun yesNo(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "skipped" }

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** Listens to the bike while the rider presses the switch; found = it changed, held, and went back. */
@Composable
private fun SwitchStep(step: CalStep.Switch, connected: Boolean, saved: SwitchSignal?, onFound: (SwitchSignal) -> Unit,
                       onAgain: () -> Unit, onNext: () -> Unit) {
    val feedback = rememberLinkFeedback()
    var seeing by remember(step.id) { mutableStateOf<Pair<String, Int>?>(null) }
    var round by remember(step.id) { mutableIntStateOf(0) }
    DisposableEffect(step.id, round, connected, saved == null) {
        val service = BleService.instance
        if (saved == null && connected && service != null) {
            val finder = SwitchFinder()
            service.frameListener = { frame ->
                val found = finder.feed(frame, System.currentTimeMillis())
                seeing = finder.seeing
                if (found != null) { service.frameListener = null; onFound(found); feedback.toggle(true) }
            }
        }
        onDispose { if (service?.frameListener != null) service.frameListener = null }
    }
    RaceCard {
        Text(step.how, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (saved != null) Icons.Outlined.CheckCircle else Icons.Outlined.TouchApp, null,
                tint = if (saved != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                saved != null -> "Got it: ${describe(saved)}"
                seeing != null -> "Seeing ${seeing!!.first.replace(".", " ")} = %02X · now let go".format(seeing!!.second)
                connected -> "Listening…"
                else -> "Waiting for the bike"
            }, fontFamily = FontFamily.Monospace)
        }
        if (saved != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ onAgain(); round++ }), Modifier.weight(1f)) { Text("Again") }
            Button(onClick = hapticAction(onNext), Modifier.weight(1f)) { Text("Next") }
        }
    }
}

/** The rider puts the bike in each gear (engine off: rock it to shift) and taps the gear the dash shows. */
@Composable
private fun GearStep(connected: Boolean, gears: MutableMap<Int, Int>, onDone: () -> Unit) {
    var raw by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(connected) {
        while (connected) {
            raw = BleService.instance?.lastDecoded?.get("5A11")?.chunked(2)?.getOrNull(5)?.toIntOrNull(16)?.and(0x0F)
            kotlinx.coroutines.delay(250)
        }
    }
    RaceCard {
        Text("Shift into each gear, then tap the gear your dash shows.", style = MaterialTheme.typography.titleMedium)
        Muted("Engine off: rock the bike back and forth to shift. Neutral first.")
        Text("Bike reports: ${raw ?: "—"}", fontFamily = FontFamily.Monospace)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..6).forEach { g ->
                val label = if (g == 0) "N" else "$g"
                val done = gears.values.contains(g)
                FilterChip(done, enabled = raw != null, onClick = hapticSelection({
                    raw?.let { r -> gears.entries.removeAll { it.value == g || it.key == r }; gears[r] = g }
                }), label = { Text(if (done) "$label · ${gears.entries.first { it.value == g }.key}" else label) })
            }
        }
        Button(onClick = hapticAction(onDone), enabled = gears.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Save gears")
        }
    }
}

@Composable
private fun FuelStep(connected: Boolean, onDone: (Int) -> Unit) {
    val reported = BleService.instance?.lastDecoded?.get("5A10")?.let { hex ->
        hex.chunked(2).getOrNull(6)?.toIntOrNull(16)
    }?.takeIf { connected }
    var now by remember { mutableIntStateOf(reported ?: 0) }
    var full by remember { mutableIntStateOf(maxOf(Calibration.DEFAULT_FUEL_MAX, reported ?: 0)) }
    RaceCard {
        Muted(if (reported != null) "The bike reports $reported right now." else "Connect to see what the bike reports.")
        Stepper("Bars on the dash right now", now, 0..12) { now = it }
        Stepper("Bars on a full tank", full, 1..12) { full = it }
        // when the dash and the bike count differently, keep the app on the bike's scale
        val scaled = if (reported != null && reported > 0 && now > 0 && now != reported) Math.round(full * reported / now.toFloat()) else full
        if (scaled != full) Muted("The bike counts differently from the dash: a full tank will read $scaled in its numbers.")
        Button(onClick = hapticAction({ onDone(scaled) }), Modifier.fillMaxWidth()) { Text("Save fuel scale") }
    }
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = hapticSelection({ onChange((value - 1).coerceIn(range)) })) { Icon(Icons.Outlined.Remove, "Fewer") }
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        IconButton(onClick = hapticSelection({ onChange((value + 1).coerceIn(range)) })) { Icon(Icons.Outlined.Add, "More") }
    }
}

@Composable
private fun YesNoStep(question: String, note: String, onAnswer: (Boolean) -> Unit) {
    RaceCard {
        Text(question, style = MaterialTheme.typography.titleMedium)
        Muted(note)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ onAnswer(false) }), Modifier.weight(1f)) { Text("No") }
            Button(onClick = hapticAction({ onAnswer(true) }), Modifier.weight(1f)) { Text("Yes") }
        }
    }
}

/** One arrow at a time on the dash; the rider says whether it matches the picture. */
@Composable
private fun ArrowStep(ui: LinkUiController, answers: MutableMap<String, Boolean>, codes: MutableMap<String, Int>, onDone: () -> Unit) {
    var i by remember { mutableIntStateOf(0) }
    val (m, label, icon) = ARROWS[i]
    // null when RevTale knows no arrow for this turn yet (roundabout): then the looks start right away
    val current = codes[m.name] ?: Pictograms.code(m, BleService.instance?.pictoOverrides().orEmpty())
    // null = the arrow RevTale uses now; else which other look is on the dash (an index into ARROW_LOOKS)
    var look by remember(i) { mutableStateOf<Int?>(if (current == null) 0 else null) }
    val others = ARROW_LOOKS[m].orEmpty().filter { it != current }
    val shown = look?.let { others.getOrNull(it) } ?: current ?: 0
    val outOfLooks = look != null && look!! >= others.size
    LaunchedEffect(i, shown, outOfLooks) { if (!outOfLooks) ui.arrow(shown) }
    fun next() { if (i < ARROWS.lastIndex) i++ else onDone() }
    RaceCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(12.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleLarge)
                Muted("Arrow ${i + 1} of ${ARROWS.size}")
            }
        }
        when {
            outOfLooks -> {
                Text("None of them fit", style = MaterialTheme.typography.titleMedium)
                Muted("RevTale will write this turn on the dash instead of drawing a wrong arrow.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = hapticAction({ look = 0 }), Modifier.weight(1f)) { Text("Look again") }
                    Button(onClick = hapticAction({ answers[m.name] = false; codes.remove(m.name); next() }), Modifier.weight(1f)) { Text("Use text") }
                }
            }
            look != null -> {
                Text(if (current == null) "Is this a ${label.lowercase()}? (${look!! + 1} of ${others.size})" else "How about now? (${look!! + 1} of ${others.size})",
                    style = MaterialTheme.typography.titleMedium)
                Muted(if (current == null) "RevTale doesn't know your dash's ${label.lowercase()} arrow yet. Until you pick one, it shows the turn as text."
                    else "Another arrow your dash can draw. Pick it if it points the way of a ${label.lowercase()}.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = hapticAction({ look = look!! + 1 }), Modifier.weight(1f)) { Text("Next one") }
                    Button(onClick = hapticAction({ answers[m.name] = true; codes[m.name] = shown; next() }), Modifier.weight(1f)) { Text("This one") }
                }
            }
            else -> {
                Text("Does the dash show this turn?", style = MaterialTheme.typography.titleMedium)
                Muted("Your dash may draw it a little differently; what matters is that it points the right way.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = hapticAction({ look = 0 }), Modifier.weight(1f)) { Text("No, it's wrong") }
                    Button(onClick = hapticAction({ answers[m.name] = true; next() }), Modifier.weight(1f)) { Text("Yes") }
                }
            }
        }
    }
}

/** On Live under the bike's name until the bike is calibrated once or the rider puts it off (then a small tag stays). */
@Composable
fun CalibrationNudge(onStart: () -> Unit, onLater: () -> Unit) {
    val on = MaterialTheme.colorScheme.onErrorContainer
    val start = hapticAction(onStart); val later = hapticAction(onLater)
    Surface(Modifier.padding(top = 6.dp).fillMaxWidth().cockpitTap("calibrate", start), shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(20.dp), tint = on)
                Spacer(Modifier.width(10.dp))
                Text("Not calibrated yet", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = on)
                IconButton(onClick = later, Modifier.cockpitTap("calibrateLater", later)) { Icon(Icons.Outlined.Close, "Not now", tint = on) }
            }
            Text("Switches, gears and the fuel gauge may read wrong until RevTale learns your bike.",
                Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = on.copy(alpha = .85f))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = start, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError)) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Calibrate now")
                }
                Spacer(Modifier.width(12.dp))
                Text("2 min, parked", style = MaterialTheme.typography.labelLarge, color = on.copy(alpha = .75f))
            }
        }
    }
}

/** Settings: calibration status and the way back in. */
@Composable
fun CalibrationCard(ui: LinkUiController, onCalibrate: () -> Unit) {
    val cal = remember(ui.calibrated) { Calibration.load(ui.context) }
    // not calibrated yet: the card says so in the warning colour, not just "you could"
    val warn = cal == null
    OutlinedCard(onClick = hapticAction(onCalibrate), modifier = Modifier.fillMaxWidth(),
        colors = if (warn) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.outlinedCardColors()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(if (warn) Icons.Outlined.ErrorOutline else Icons.Outlined.Tune, null,
                tint = if (warn) MaterialTheme.colorScheme.onErrorContainer else LocalContentColor.current)
            Column(Modifier.weight(1f)) {
                Text(if (warn) "Bike not calibrated yet" else "Calibration", style = MaterialTheme.typography.titleMedium,
                    color = if (warn) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface)
                if (warn) Text("Using Apache RTR 160 4V defaults, so switches, gears or the fuel gauge may read wrong. Two minutes, parked.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                else Muted("Calibrated ${SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(cal!!.atMs))} · ${cal.bikeName}. Redo all or one thing.")
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}
