@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Rides tab: mileage and spend from the fill-ups the rider logged. */
@Composable
fun FuelCard(ui: LinkUiController) {
    var fills by remember { mutableStateOf<List<FillUp>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    // reload after each save (the snackbar notice changes) and when a prompt is answered
    LaunchedEffect(ui.notice, ui.state.refuel) { fills = ui.fills() }
    val f = remember(fills) { FuelStats.summarize(fills) }
    RaceCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Eyebrow("Fuel")
            Icon(Icons.Outlined.LocalGasStation, null, tint = MaterialTheme.colorScheme.primary)
        }
        if (f.count == 0) {
            Muted("Log two fill-ups (or just fill up: the gauge rise is noticed) to get your real km/l and cost per km.")
        } else {
            Text(f.kmPerL?.let { "${decimal(it)} km/l" } ?: "Mileage after your next fill-up", style = MaterialTheme.typography.headlineLarge)
            StatGrid(listOfNotNull(
                f.costPerKm?.let { "Cost per km" to "₹${decimal(it, 2)}" },
                "This month" to "₹${decimal(f.spentThisMonth, 0)}",
                "Liters this month" to decimal(f.litersThisMonth),
                "Fill-ups" to "${f.count}"))
            f.last?.let { l ->
                Muted("Last: ${SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(l.tMs))} · ${if (l.liters > 0) "${decimal(l.liters, 2)} L at ₹${decimal(l.pricePerL, 2)}/L" else "amount unknown"} · " +
                    "odometer ${decimal(l.odoKm)} km")
            }
            Muted("Most accurate when you fill the tank full each time.")
            TankHistory(fills)
        }
        OutlinedButton(onClick = hapticAction({ adding = true })) { Text("Add a fill-up") }
    }
    if (adding) FuelDialog(ui, null) { adding = false }
}

/** Asks what was filled: amount paid (₹) or liters, plus the price per liter (remembered; asked the first time). */
@Composable
fun FuelDialog(ui: LinkUiController, refuel: PendingRefuel?, onClose: () -> Unit) {
    var isAmount by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf("") }
    var price by remember { mutableStateOf(ui.state.fuelPrice?.let { decimal(it, 2) } ?: "") }
    var odo by remember { mutableStateOf((refuel?.odoKm ?: ui.odometerNow())?.let { decimal(it) } ?: "") }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    val liters = num(value)?.let { v -> FillUp.liters(v, isAmount, num(price) ?: 0.0) }
    AlertDialog(onDismissRequest = onClose,
        icon = { Icon(Icons.Outlined.LocalGasStation, null) },
        title = { Text(if (refuel != null) "Confirm a fill-up" else "Add a fill-up") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (refuel != null) Muted("The gauge went from ${refuel.barsFrom} to ${refuel.barsTo} bars. How much did you fill? " +
                    "Not sure (someone else filled it)? Tap Don't know." +
                    if (refuel.simulated) " (Emulator: this won't be saved.)" else "")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(isAmount, hapticSelection({ isAmount = true }), label = { Text("Amount ₹") })
                    FilterChip(!isAmount, hapticSelection({ isAmount = false }), label = { Text("Liters") })
                }
                OutlinedTextField(value, { value = it.take(8) }, singleLine = true,
                    label = { Text(if (isAmount) "Amount paid (₹)" else "Liters filled") },
                    isError = value.isNotBlank() && (num(value) ?: 0.0) <= 0,
                    supportingText = { if (value.isNotBlank() && (num(value) ?: 0.0) <= 0) Text("Enter a number greater than zero") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(price, { price = it.take(7) }, singleLine = true, label = { Text("Petrol price per liter (₹)") },
                    isError = price.isNotBlank() && (num(price) ?: 0.0) <= 0,
                    supportingText = { Text(if (ui.state.fuelPrice == null) "First time: enter today's rate. It's remembered." else "Change it if the rate changed.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(odo, { odo = it.take(9) }, singleLine = true, label = { Text("Odometer (km)") },
                    isError = odo.isNotBlank() && (num(odo) ?: 0.0) <= 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                if (liters != null) Text("= ${decimal(liters, 2)} L" + if (!isAmount) " · ₹${decimal(liters * (num(price) ?: 0.0), 0)}" else "",
                    style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = hapticAction({
                if (ui.saveFill(num(value) ?: 0.0, isAmount, num(price) ?: 0.0, num(odo) ?: 0.0, refuel)) onClose()
            }), enabled = liters != null && (num(price) ?: 0.0) > 0 && (num(odo) ?: 0.0) > 0) { Text("Save") }
        },
        dismissButton = {
            if (refuel != null) Row {
                // someone else filled it (a friend on your bike) and you don't know how much: skip it, mileage stays right
                TextButton(onClick = hapticAction({ ui.skipFill(refuel); onClose() })) { Text("Don't know") }
                TextButton(onClick = hapticAction({ ui.dismissRefuel(); onClose() })) { Text("Not a fill-up") }
            } else TextButton(onClick = hapticAction(onClose)) { Text("Cancel") }
        })
}
