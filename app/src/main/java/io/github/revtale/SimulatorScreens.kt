@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch

/** Developer → Emulator: start a pretend bike and watch what the app puts on the cluster. */
fun LazyListScope.devEmulator(ui: LinkUiController) {
    val state = ui.state
    // while running, the cluster is pinned above this list (see DeveloperScreen)
    if (!state.simulating) {
        item {
            RaceCard {
                SectionTitle("Bike emulator", "A pretend bike streams the same frames as yours, so the app behaves as if it were connected. " +
                    "Everything the app would send to the cluster is drawn on a virtual cluster instead. Nothing goes over Bluetooth.")
                Button(onClick = hapticAction(ui::startSimulator), enabled = !ui.busy && !state.connected, modifier = Modifier.fillMaxWidth()) {
                    Text("Start emulator")
                }
                if (state.connected) Muted("Disconnect from your bike first.")
                else if (state.waiting) Muted("Waiting for your bike pauses while the emulator runs, and resumes when you stop it.")
            }
        }
        item { EmulatorGuide() }
        return
    }
    item { EmulatorMessage(ui) }
    item { EmulatorTests(ui) }
    item { EmulatorControls(ui) }
    item { EmulatorGuide() }
}

@Composable
private fun EmulatorControls(ui: LinkUiController) {
    val state = ui.state
    RaceCard {
        SectionTitle("Ride it", "Auto ride loops a short city route: launches, stops and turns.")
        SettingSwitch("Auto ride", "Off: hold a speed with the slider.", state.simAuto) { on -> ui.simulate { it.auto = on } }
        if (!state.simAuto) {
            var target by remember { mutableFloatStateOf(state.simTarget.toFloat()) }
            Text("Target ${target.toInt()} km/h", style = MaterialTheme.typography.titleMedium)
            Slider(target, hapticSlider(target, 0f..120f) { target = it; ui.simulate { b -> b.targetKmh = it.toInt() } }, valueRange = 0f..120f,
                modifier = Modifier.semantics { contentDescription = "Simulated target speed" })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 30, 60, 90).forEach { v ->
                    OutlinedButton(onClick = hapticAction({ target = v.toFloat(); ui.simulate { it.targetKmh = v } }), modifier = Modifier.weight(1f)) { Text("$v") }
                }
            }
        }
        OutlinedButton(onClick = hapticAction({ ui.simulate { it.overheat() } }), modifier = Modifier.fillMaxWidth()) { Text("Overheat the engine (160 °C)") }
        var engine by remember { mutableStateOf(BleService.instance?.simBike?.engineOn ?: true) }
        SettingSwitch("Engine running", "Switch off after riding 500 m+: the trip summary shows on the cluster after 10 s.", engine) { on ->
            engine = on; ui.simulate { it.engineOn = on }
        }
        Text("Fuel gauge · ${state.live.fuel ?: "-"} bars", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ ui.simulate { it.fuelBars = (it.fuelBars - 1).coerceAtLeast(0) } }), modifier = Modifier.weight(1f)) { Text("Use fuel") }
            Button(onClick = hapticAction({ ui.simulate { it.fuelBars = 6 } }), modifier = Modifier.weight(1f)) { Text("Fill up") }
        }
        Muted("After a fill-up the app asks what you filled (5 s here; a minute of riding on the bike).")
        Text("Riding mode", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 2, 3).forEach { m ->
                FilterChip(state.live.mode == m, onClick = hapticAction({ ui.simulate { it.mode = m } }), label = { Text(RideModes.name(m)) })
            }
        }
    }
}

@Composable
private fun EmulatorMessage(ui: LinkUiController) {
    var message by remember { mutableStateOf("HELLO") }
    RaceCard {
        SectionTitle("Say it on the dash", "Goes to the custom line, exactly as from the Cluster tab.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(message, { message = it.take(17) }, singleLine = true, label = { Text("Message") }, modifier = Modifier.weight(1f))
            Button(onClick = hapticAction({ ui.bikeCommand { it.sendTextSlot(Protocol.ID_CUSTOM_LINE, Protocol.clusterText(message)) } }),
                enabled = Protocol.clusterText(message).isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun EmulatorTests(ui: LinkUiController) {
    val s = BleService.instance
    fun send(action: (BleService) -> String) { s?.takeIf { it.simulating }?.let { ui.notice = action(it); ui.refresh() } }
    RaceCard {
        SectionTitle("Try things", "The same calls the app makes for real. Google Maps directions also work: start a route in Maps.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ send { it.onCallState(android.telephony.TelephonyManager.EXTRA_STATE_RINGING, "+919876543210", "Mom ❤️", real = false); "Ringing: Mom ❤️" } }),
                modifier = Modifier.weight(1f)) { Text("Call") }
            OutlinedButton(onClick = hapticAction({ send { it.sendTextSlot(0x53, "ON MY WAY"); it.unreadSms = (it.unreadSms + 1).coerceAtMost(99); "SMS sent, unread +1" } }),
                modifier = Modifier.weight(1f)) { Text("SMS") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ send { it.onNavInfo(NavInfo(Maneuver.RIGHT, 250, 12, 4300, "Turn right onto MG Road", "MG Road")); "Maps: turn right" } }),
                modifier = Modifier.weight(1f)) { Text("Maps right") }
            OutlinedButton(onClick = hapticAction({ send { it.onNavInfo(NavInfo(Maneuver.LEFT, 90, 11, 4100, "Turn left", null)); "Maps: turn left" } }),
                modifier = Modifier.weight(1f)) { Text("Maps left") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ send { it.onNavEnded(); "Maps route ended" } }), modifier = Modifier.weight(1f)) { Text("End route") }
            OutlinedButton(onClick = hapticAction({ send { it.sendTextSlot(0x62, "NOW PLAYING") } }), modifier = Modifier.weight(1f)) { Text("Music") }
        }
        val scope = rememberCoroutineScope()
        fun info(holdMs: Long) = scope.launch { ui.simulate { it.infoHeld = true }; kotlinx.coroutines.delay(holdMs); ui.simulate { it.infoHeld = false } }
        Text("Handlebar i switch", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = hapticAction({ info(700) }), modifier = Modifier.weight(1f)) { Text("Tap i", maxLines = 1) }
            OutlinedButton(onClick = hapticAction({ info(2500) }), modifier = Modifier.weight(1f)) { Text("Hold i") }
            OutlinedButton(onClick = hapticAction({ send { it.onCallState(android.telephony.TelephonyManager.EXTRA_STATE_IDLE, null, real = false); "Call missed" } }),
                modifier = Modifier.weight(1f)) { Text("Miss") }
        }
        OutlinedButton(onClick = hapticAction({ send { it.onSmsReceived("+919876543210", "Ok beta, ride safe. Call me when you reach."); "Mom texted back" } }),
            modifier = Modifier.fillMaxWidth()) { Text("Mom replies by SMS") }
        Muted("Call rings as \"Mom ❤️\": the heart can't be drawn, so the cluster shows MOM (the log notes the dropped heart). " +
            "Press i declines; then Hold i texts back. In the emulator nothing is really declined or sent.")
    }
}

@Composable
private fun EmulatorGuide() {
    RaceCard {
        SectionTitle("What the emulator covers")
        GuideRow("Works like the real bike", "Handshake and key detection, live speed, RPM, gear and mode, the Live screen, " +
            "the ride notification (Now Bar), phone link (name, clock, battery, signal, unread), messages, Maps directions, arrow test, compass.")
        GuideRow("Only on the real bike", "How the cluster really draws each frame (arrow shapes, fonts, where text lands), " +
            "pairing, Bluetooth range and reconnects. The drawing here is the app's reading of its own frames.")
        GuideRow("Not saved", "Emulated rides are not added to your ride history.")
    }
}

@Composable
private fun GuideRow(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Muted(detail) }
}

private val Lcd = Color(0xFF07090C)
private val LcdText = Color(0xFFE9F2FF)
private val LcdDim = Color(0xFF7D8896)
private val LcdRed = Color(0xFFFF2D3C)

/** The cluster as the app's frames describe it: telemetry from the (pretend) bike, everything else from the phone.
 *  Compact so it can stay pinned above the emulator controls while you try things. */
@Composable
fun EmulatorCluster(ui: LinkUiController) {
    val state = ui.state
    val c = state.cluster ?: ClusterView()
    val l = state.live
    val now = System.currentTimeMillis()
    Surface(color = Lcd, shape = RoundedCornerShape(22.dp), border = BorderStroke(2.dp, Color(0xFF2A2F37)),
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "Emulated cluster" }) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // status row: clock · signal · phone battery · unread · stop
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LcdLabel(c.clock, Modifier.weight(1f))
                if (c.signal >= 0) Bars(c.signal, 4, "SIG")
                if (c.phoneBattery >= 0) Bars(c.phoneBattery, 5, "BAT")
                if (c.unread > 0) LcdLabel("✉ ${c.unread}", color = LcdRed)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text("${l.speed ?: 0}", color = LcdText, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 46.sp)
                    LcdLabel("KM/H")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val rpm = l.rpm ?: 0
                    LinearProgressIndicator(progress = { (rpm / 11000f).coerceIn(0f, 1f) }, color = if (rpm >= 9000) LcdRed else LcdText,
                        trackColor = Color(0xFF1B2028), modifier = Modifier.fillMaxWidth().height(8.dp))
                    LcdLabel("$rpm RPM")
                    LcdLabel("${l.temperature ?: "-"}°C · ${decimal(l.volts)} V")
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(l.gear?.let { if (it == 0) "N" else "$it" } ?: "-", color = if (l.gear == 0) Color(0xFF4CD964) else LcdText,
                        fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 40.sp)
                    LcdLabel(l.mode?.let { RideModes.name(it).uppercase(Locale.US) } ?: "", color = if (l.mode == 2) LcdRed else LcdDim)
                }
            }
            if (c.navOn) NavPanel(c)
            // text line (custom line / feedback) with the rider name, and pop-ups from the other slots
            Surface(color = Color(0xFF11151B), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.line.ifBlank { " " }, color = LcdText, fontFamily = FontFamily.Monospace, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    LcdLabel(c.feedbackId?.let { "FB $it" } ?: c.riderName)
                }
            }
            if (c.popupLabel != null && now - c.popupAt < 8000) Surface(color = LcdRed.copy(alpha = .18f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("${c.popupLabel.uppercase(Locale.US)}  ${c.popupText}", color = LcdText, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            // the cluster's built-in SMS result banner (5B4A b5)
            if (c.smsStatus in 1..2) Surface(color = LcdRed.copy(alpha = .18f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text(if (c.smsStatus == 1) "SMS SENT SUCCESS" else "SMS NOT SENT", color = LcdText, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LcdLabel("ODO ${decimal(l.odometer)} · TRIP ${decimal(l.trip)} · ${c.frames} frames", Modifier.weight(1f))
                Text("STOP", color = LcdRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClickLabel = "Stop emulator", onClick = hapticAction(ui::stopSimulator)).padding(4.dp))
            }
        }
    }
}

@Composable
private fun NavPanel(c: ClusterView) {
    val overrides = BleService.instance?.pictoOverrides() ?: emptyMap()
    val maneuver = (Pictograms.CONFIRMED + overrides).entries.firstOrNull { it.value == c.navPicto }?.key
    Surface(color = Color(0xFF11151B), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(56.dp).background(Color(0xFF1B2028), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                val icon = maneuver?.let(::arrowIcon)
                val heading = c.navPicto - Pictograms.HEADING_NORTH
                if (icon != null) Icon(icon, null, tint = LcdText, modifier = Modifier.size(40.dp))
                else if (heading in 0..7) Icon(Icons.Outlined.Navigation, null, tint = LcdText,
                    modifier = Modifier.size(40.dp).rotate(heading * 45f))
                else Text(if (c.navPicto < 0) "…" else "#${c.navPicto}", color = LcdRed, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (c.navText.isNotBlank()) Text(c.navText, color = LcdText, fontFamily = FontFamily.Monospace)
                val dist = if (c.navDistM >= 1000) "${decimal(c.navDistM / 1000.0)} KM" else "${c.navDistM} M"
                Text(dist, color = LcdText, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                LcdLabel(listOfNotNull("${c.navMinutesLeft} MIN", c.navDistLeftM.takeIf { it > 0 }?.let { "${decimal(it / 1000.0)} KM" }, "P${c.navPrefix}").joinToString(" · "))
                if (c.navPicto >= 0) {
                    val name = Pictograms.label(c.navPicto)
                    LcdLabel("arrow ${c.navPicto}: " + (name ?: "blank on the real cluster"), color = if (name == null) LcdRed else LcdDim)
                }
            }
        }
    }
}

private fun arrowIcon(m: Maneuver): ImageVector = when (m) {
    Maneuver.STRAIGHT -> Icons.Outlined.Straight
    Maneuver.SLIGHT_LEFT, Maneuver.KEEP_LEFT -> Icons.Outlined.TurnSlightLeft
    Maneuver.LEFT -> Icons.Outlined.TurnLeft
    Maneuver.SHARP_LEFT -> Icons.Outlined.TurnSharpLeft
    Maneuver.SLIGHT_RIGHT, Maneuver.KEEP_RIGHT -> Icons.Outlined.TurnSlightRight
    Maneuver.RIGHT -> Icons.Outlined.TurnRight
    Maneuver.SHARP_RIGHT -> Icons.Outlined.TurnSharpRight
    Maneuver.UTURN_LEFT -> Icons.Outlined.UTurnLeft
    Maneuver.UTURN_RIGHT -> Icons.Outlined.UTurnRight
    Maneuver.ROUNDABOUT -> Icons.Outlined.RoundaboutRight
    Maneuver.EXIT_LEFT -> Icons.Outlined.ForkLeft
    Maneuver.EXIT_RIGHT -> Icons.Outlined.ForkRight
    Maneuver.MERGE -> Icons.Outlined.Merge
    Maneuver.DESTINATION -> Icons.Outlined.Flag
    Maneuver.UNKNOWN -> Icons.Outlined.QuestionMark
}

@Composable
private fun LcdLabel(text: String, modifier: Modifier = Modifier, color: Color = LcdDim) {
    Text(text, modifier, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1)
}

@Composable
private fun Bars(level: Int, max: Int, label: String) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$label $level of $max" }) {
        LcdLabel(label)
        repeat(max) { i ->
            Box(Modifier.width(4.dp).height((5 + i * 3).dp).background(if (i < level) LcdText else Color(0xFF2A2F37)))
        }
    }
}
