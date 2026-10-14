package io.github.revtale

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import androidx.compose.ui.graphics.luminance
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.CopyrightOverlay
import java.io.File

// bright bands with a dark casing underneath so the route reads on both the dark and the light basemap
private val SpeedColors = listOf(0xFF4FC3F7.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8A3D.toInt(), 0xFFFF2D3C.toInt())

/** Dark theme: OpenStreetMap tiles inverted and toned down to a night map, so the bright route stands out. */
private val NightMap = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
    -0.85f, 0f, 0f, 0f, 235f,
    0f, -0.85f, 0f, 0f, 235f,
    0f, 0f, -0.80f, 0f, 235f,
    0f, 0f, 0f, 1f, 0f)).apply { postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.25f) }) })
private fun speedBand(speed: Int) = (speed / 30).coerceIn(0, 3)
private fun validLocation(s: Sample) = s.lat.isFinite() && s.lon.isFinite() && s.lat in -85.0..85.0 && s.lon in -180.0..180.0
private data class RouteLine(val band: Int, val points: MutableList<GeoPoint>)
private data class RouteDrawing(val lines: List<RouteLine>, val bounds: List<GeoPoint>)

private fun routeDrawing(samples: List<Sample>): RouteDrawing {
    val lines = mutableListOf<RouteLine>()
    val bounds = mutableListOf<GeoPoint>()
    for (i in 0 until samples.lastIndex) {
        val a = samples[i]; val b = samples[i + 1]
        if (!validLocation(a) || !validLocation(b) || b.tMs - a.tMs !in 1..5000) continue
        val p = GeoPoint(a.lat, a.lon); val q = GeoPoint(b.lat, b.lon)
        val band = speedBand(a.speedKmh)
        val previous = lines.lastOrNull()
        if (previous != null && previous.band == band && previous.points.last() == p) previous.points.add(q)
        else lines.add(RouteLine(band, mutableListOf(p, q)))
        bounds.add(p); bounds.add(q)
    }
    if (bounds.isEmpty()) samples.firstOrNull(::validLocation)?.let { bounds.add(GeoPoint(it.lat, it.lon)) }
    return RouteDrawing(lines, bounds)
}

@Composable
fun RideRouteMap(samples: List<Sample>, spots: List<Spot>) {
    val route by produceState<RouteDrawing?>(null, samples) { value = withContext(Dispatchers.Default) { routeDrawing(samples) } }
    RaceCard {
        when {
            route == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            route!!.bounds.isEmpty() -> Muted("No GPS route in this ride. Allow precise location and wait for a GPS fix before your next ride. Your speed and gear analysis is still available.")
            else -> {
                RouteView(route!!, spots, MaterialTheme.colorScheme.background.luminance() < 0.5f)
                Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
                listOf("0–29 km/h", "30–59 km/h", "60–89 km/h", "90+ km/h").chunked(2).forEachIndexed { row, labels ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        labels.forEachIndexed { i, label ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                                Box(Modifier.size(12.dp).background(Color(SpeedColors[row * 2 + i])))
                                Text(label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Muted("Drag to explore, pinch to zoom. Map tiles need internet; cached tiles and your route remain available offline. Gaps in GPS are not connected.")
            }
        }
    }
}

@Composable
private fun RouteView(route: RouteDrawing, spots: List<Spot>, dark: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember(route, dark) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "maps")
            osmdroidTileCache = File(context.cacheDir, "maps/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            if (dark) overlayManager.tilesOverlay.setColorFilter(NightMap)
            setMultiTouchControls(true)
            minZoomLevel = 3.0; maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            val density = resources.displayMetrics.density
            route.lines.forEach { line ->
                overlays.add(Polyline(this).apply {
                    setPoints(line.points)
                    outlinePaint.color = if (dark) 0xFF000000.toInt() else 0xE6202024.toInt()
                    outlinePaint.strokeWidth = 10 * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
            }
            route.lines.forEach { line ->
                overlays.add(Polyline(this).apply {
                    setPoints(line.points)
                    outlinePaint.color = SpeedColors[line.band]
                    outlinePaint.strokeWidth = 6 * density
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                })
            }
            // start (white) and finish (red) of the ride
            listOfNotNull(route.lines.firstOrNull()?.points?.firstOrNull()?.let { it to 0xFFFFFFFF.toInt() },
                route.lines.lastOrNull()?.points?.lastOrNull()?.let { it to 0xFFE5202E.toInt() }).forEachIndexed { i, (point, color) ->
                overlays.add(Marker(this).apply {
                    position = point
                    title = if (i == 0) "Start" else "Finish"
                    val side = (18 * density).toInt()
                    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    Canvas(bitmap).apply {
                        paint.color = 0xFF0B0B0C.toInt(); drawCircle(side / 2f, side / 2f, side / 2f, paint)
                        paint.color = color; drawCircle(side / 2f, side / 2f, side / 2f - 3 * density, paint)
                    }
                    icon = BitmapDrawable(resources, bitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }
            spots.take(10).forEachIndexed { index, spot ->
                if (spot.lat.isFinite() && spot.lon.isFinite()) overlays.add(Marker(this).apply {
                    position = GeoPoint(spot.lat, spot.lon)
                    title = "${index + 1}. ${spot.topSpeed} km/h peak"
                    snippet = "Average ${spot.avgSpeed} km/h · ${spot.passes} samples"
                    val side = (26 * resources.displayMetrics.density).toInt()
                    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    Canvas(bitmap).apply {
                        paint.color = 0xFF0B0B0C.toInt(); drawCircle(side / 2f, side / 2f, side / 2f, paint)
                        paint.color = 0xFFE5202E.toInt(); drawCircle(side / 2f, side / 2f, side / 2f - 2, paint)
                        paint.color = 0xFFFFFFFF.toInt(); paint.textSize = side * .46f; paint.textAlign = Paint.Align.CENTER; paint.isFakeBoldText = true
                        drawText("${index + 1}", side / 2f, side / 2f - (paint.descent() + paint.ascent()) / 2, paint)
                    }
                    icon = BitmapDrawable(resources, bitmap)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                })
            }
            overlays.add(CopyrightOverlay(context))
            controller.setZoom(14.0)
            controller.setCenter(route.bounds.first())
            // only after layout: zooming a 0×0 map makes osmdroid's projection loop forever (froze the app)
            addOnFirstLayoutListener { _, _, _, _, _ ->
                if (width > 0 && height > 0 && route.bounds.distinct().size > 1)
                    zoomToBoundingBox(BoundingBox.fromGeoPoints(route.bounds), false, (24 * resources.displayMetrics.density).toInt())
            }
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
        }
    }
    DisposableEffect(map, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) { Lifecycle.Event.ON_RESUME -> map.onResume(); Lifecycle.Event.ON_PAUSE -> map.onPause(); else -> Unit }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) map.onResume()
        onDispose { lifecycle.removeObserver(observer); map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(300.dp).clipToBounds().semantics {
        contentDescription = "Ride route coloured by speed with ${minOf(10, spots.size)} fastest spot markers. Coordinates and speeds are listed below."
    })
}
