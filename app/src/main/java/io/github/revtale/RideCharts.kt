@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GearColors = listOf(Color(0xFF8C9692), Color(0xFFB7DD45), Color(0xFF67B9B0),
    Color(0xFF79A8D2), Color(0xFFB29CCB), Color(0xFFD0AC6B), Color(0xFFD88686))

private data class GearBand(val start: Long, val end: Long, val gear: Int)
private data class Plot(val segments: List<List<Sample>>, val bands: List<GearBand>)

/** Keep extrema, gear changes and genuine gaps while bounding the trace's drawing work. */
private fun preparePlot(samples: List<Sample>, rpm: Boolean): Plot {
    if (samples.isEmpty()) return Plot(emptyList(), emptyList())
    val groups = mutableListOf<MutableList<Sample>>()
    val bands = mutableListOf<GearBand>()
    for ((index, sample) in samples.withIndex()) {
        if (index == 0 || sample.tMs - samples[index - 1].tMs > 5000) groups.add(mutableListOf())
        groups.last().add(sample)
        val end = samples.getOrNull(index + 1)?.tMs?.coerceAtMost(sample.tMs + 5000) ?: sample.tMs
        val prev = bands.lastOrNull()
        if (prev != null && prev.gear == sample.gear && prev.end == sample.tMs)
            bands[bands.lastIndex] = prev.copy(end = end)
        else bands.add(GearBand(sample.tMs, end, sample.gear))
    }
    val chunk = (samples.size / 500).coerceAtLeast(1)
    val segments = groups.map { group ->
        group.chunked(chunk).flatMap { bucket ->
            listOf(bucket.first(), bucket.minBy { if (rpm) it.rpm else it.speedKmh },
                bucket.maxBy { if (rpm) it.rpm else it.speedKmh }, bucket.last()).distinctBy { it.tMs }.sortedBy { it.tMs }
        }
    }
    return Plot(segments, bands)
}

@Composable
fun RideTrace(samples: List<Sample>, rpm: Boolean) {
    val plot by produceState<Plot?>(null, samples, rpm) { value = withContext(Dispatchers.Default) { preparePlot(samples, rpm) } }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val first = samples.firstOrNull()?.tMs ?: 0L
    val last = samples.lastOrNull()?.tMs ?: first
    val elapsed = (last - first).coerceAtLeast(1)
    val maximum = remember(samples, rpm) { (samples.maxOfOrNull { if (rpm) it.rpm else it.speedKmh } ?: 0).coerceAtLeast(if (rpm) 1000 else 10) }
    val name = if (rpm) "RPM" else "Speed · km/h"
    RaceCard {
        SectionTitle(name)
        if (samples.isEmpty()) Muted("No samples recorded.")
        else {
            Row(Modifier.height(180.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Muted("$maximum"); Muted("${maximum / 2}"); Muted("0")
                }
                Canvas(Modifier.weight(1f).fillMaxHeight().semantics {
                    contentDescription = "$name over ${duration(elapsed / 1000.0)}. Peak $maximum. Gear bands are listed below. Use Inspect a moment for individual values."
                }) {
                    fun x(t: Long) = ((t - first).toDouble() / elapsed * size.width).toFloat()
                    plot?.bands?.forEach { band ->
                        drawRect(GearColors[band.gear.coerceIn(0, 6)].copy(alpha = .17f),
                            topLeft = Offset(x(band.start), 0f), size = Size((x(band.end) - x(band.start)).coerceAtLeast(0f), size.height))
                    }
                    repeat(3) { i -> drawLine(grid, Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1.dp.toPx()) }
                    plot?.segments?.forEach { segment ->
                        val path = Path()
                        segment.forEachIndexed { index, sample ->
                            val y = size.height * (1 - (if (rpm) sample.rpm else sample.speedKmh).toFloat() / maximum)
                            if (index == 0) path.moveTo(x(sample.tMs), y) else path.lineTo(x(sample.tMs), y)
                        }
                        drawPath(path, primary, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Muted("0:00"); Muted("Elapsed · ${duration(elapsed / 1000.0)}") }
            val gears = remember(samples) { samples.map { it.gear }.distinct().sorted() }
            gears.chunked(4).forEach { row ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { gear ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Box(Modifier.size(12.dp).background(GearColors[gear.coerceIn(0, 6)]))
                            Text(if (gear == 0) "N" else "G$gear", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistogramCard(title: String, subtitle: String, data: Map<Int, Double>, bandWidth: Int, unit: String) {
    val total = data.values.sum()
    val peak = (data.values.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    RaceCard {
        SectionTitle(title, subtitle)
        if (data.isEmpty()) Muted("No data in these bands.")
        data.toSortedMap().forEach { (band, seconds) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$band–${band + bandWidth - 1} $unit", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("${duration(seconds)} · ${decimal(if (total > 0) seconds * 100 / total else 0.0, 0)}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
                Canvas(Modifier.fillMaxWidth().height(12.dp)) {
                    drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                    if (seconds > 0) drawRoundRect(primary, size = Size(size.width * (seconds / peak).toFloat(), size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                }
            }
        }
    }
}
