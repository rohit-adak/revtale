package io.github.revtale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

// Bike livery: matte black body, racing-red decals, gold USD fork, white graphics.
val RacingRed = Color(0xFFE5202E)
val ForkGold = Color(0xFFD4A62A)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF5964), onPrimary = Color(0xFF400009),
    primaryContainer = Color(0xFF4A0E12), onPrimaryContainer = Color(0xFFFFB3B0),
    secondary = ForkGold, onSecondary = Color(0xFF241A00),
    secondaryContainer = Color(0xFF3D3000), onSecondaryContainer = Color(0xFFFFE08A),
    tertiary = ForkGold, onTertiary = Color(0xFF241A00),
    background = Color(0xFF0B0B0C), onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF161618), onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF222225), onSurfaceVariant = Color(0xFFB4B4BA),
    surfaceContainer = Color(0xFF202023), surfaceContainerHigh = Color(0xFF29292D),
    surfaceContainerHighest = Color(0xFF333337), surfaceContainerLow = Color(0xFF161618),
    inverseSurface = Color(0xFFE7E7EA), inverseOnSurface = Color(0xFF202023), inversePrimary = Color(0xFFC0121F),
    outlineVariant = Color(0xFF333337), outline = Color(0xFF8A8A92)
)
private val LightColors = lightColorScheme(
    primary = Color(0xFFC0121F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD7), onPrimaryContainer = Color(0xFF410005),
    secondary = Color(0xFF8A6A00), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE08A), onSecondaryContainer = Color(0xFF2B2100),
    tertiary = Color(0xFF8A6A00), onTertiary = Color.White,
    background = Color(0xFFF3F3F4), onBackground = Color(0xFF18181A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF18181A),
    surfaceVariant = Color(0xFFE4E4E7), onSurfaceVariant = Color(0xFF52525A),
    surfaceContainer = Color(0xFFEDEDEF), surfaceContainerHigh = Color(0xFFE7E7EA),
    surfaceContainerHighest = Color(0xFFE1E1E5), surfaceContainerLow = Color(0xFFF7F7F8),
    inverseSurface = Color(0xFF29292D), inverseOnSurface = Color(0xFFF4F4F5), inversePrimary = Color(0xFFFF8589),
    outlineVariant = Color(0xFFCFCFD4), outline = Color(0xFF71717A)
)

@Composable
fun RacingTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(
            displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 88.sp, letterSpacing = (-5).sp),
            headlineLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
            headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp)
        ), content = content)
}

@Composable
fun RacingMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(30.dp, 22.dp).clearAndSetSemantics {}) {
        repeat(3) { i ->
            val x = i * size.width / 3
            drawPath(Path().apply {
                moveTo(x + 7.dp.toPx(), 0f); lineTo(x + 11.dp.toPx(), 0f)
                lineTo(x + 4.dp.toPx(), size.height); lineTo(x, size.height); close()
            }, color)
        }
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(text.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
fun PageHeading(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(kicker)
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        subtitle?.let { Muted(it) }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun RaceCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun SectionTitle(title: String, trailing: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        trailing?.let { Muted(it) }
    }
}

@Composable
fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.SansSerif)
                            Muted(label)
                        }
                    }
                }
                if (row.size < columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = hapticToggle(onChange)).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(description)
        }
        Switch(checked, null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun StatusBadge(text: String, active: Boolean) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

fun decimal(value: Double?, digits: Int = 1): String =
    if (value == null || !value.isFinite()) "—" else String.format(Locale.getDefault(), "%.${digits}f", value)

fun duration(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "${s / 3600}h ${s % 3600 / 60}m" else "${s / 60}m ${s % 60}s"
}

/** Optional extra inset for screens hosted outside the standard scaffold. */
val LocalBottomInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** Shared page gutters; the scaffold handles navigation and system bar clearance. */
@Composable
fun barPadding() = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + LocalBottomInset.current)
