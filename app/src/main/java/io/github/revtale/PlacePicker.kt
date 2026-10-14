@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.revtale

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A place shared from Google Maps (or found by search), before the rider names and saves it. */
data class PlaceDraft(val lat: Double, val lon: Double, val name: String = "")

/**
 * Google Maps' "Share" link → coordinates, without any API key: follow the short link's redirects and read the
 * coordinates out of the full URL. Parsing is pure and unit-tested.
 */
object MapsLink {
    private val AT = Regex("@(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
    private val PIN = Regex("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)")
    private val Q = Regex("[?&](?:q|ll|query|destination)=(-?\\d{1,2}\\.\\d+)(?:,|%2C)\\s*(-?\\d{1,3}\\.\\d+)")
    private val GEO = Regex("^geo:(-?\\d{1,2}\\.\\d+),(-?\\d{1,3}\\.\\d+)")
    private val PLACE = Regex("/maps/place/([^/@?]+)")

    fun url(text: String): String? = Regex("https?://\\S+").find(text)?.value

    /** The pin's coordinates (preferred over the map's view centre "@…"). */
    fun coords(url: String): Pair<Double, Double>? =
        listOf(PIN, GEO, Q, AT).firstNotNullOfOrNull { r -> r.find(url)?.let { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() } }

    fun placeName(url: String): String? = PLACE.find(url)?.groupValues?.get(1)
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.replace('+', ' ')?.takeIf { it.isNotBlank() && coords(it) == null }

    /** The first line of a shared text is the place's name ("Central Library\nAddress\nhttps://…"). */
    fun sharedName(text: String): String? = text.lineSequence().map { it.substringBefore("http").trim() }.firstOrNull { it.isNotBlank() }

    /** Follows maps.app.goo.gl / goo.gl short links (blocking, off the main thread) and returns every URL on the way. */
    fun expand(url: String): List<String> {
        val seen = mutableListOf(url)
        var u = url
        repeat(6) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 6000; readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) RevTale")
            }
            try {
                val next = c.getHeaderField("Location")
                if (c.responseCode !in 300..399 || next == null) return seen
                u = URL(URL(u), next).toString(); seen += u
            } finally { c.disconnect() }
        }
        return seen
    }

    /** Text shared from Google Maps → a draft place; null when nothing points anywhere. Blocking. */
    fun resolve(ctx: Context, text: String): PlaceDraft? {
        val link = url(text)
        val urls = link?.let { runCatching { expand(it) }.getOrDefault(listOf(it)) } ?: emptyList()
        val name = sharedName(text) ?: urls.firstNotNullOfOrNull(::placeName) ?: ""
        urls.reversed().firstNotNullOfOrNull(::coords)?.let { return PlaceDraft(it.first, it.second, name) }
        // no coordinates in the link (some shares only carry a name / address): ask Android's geocoder
        val query = text.lineSequence().filter { !it.trim().startsWith("http") }.joinToString(", ").ifBlank { name }
        return Geo.search(ctx, query).firstOrNull()?.let { PlaceDraft(it.lat, it.lon, name) }
    }
}

/** Search a place by name or address with Android's built-in geocoder (free, no key). */
object Geo {
    data class Hit(val label: String, val lat: Double, val lon: Double)

    @Suppress("DEPRECATION")
    fun search(ctx: Context, query: String): List<Hit> = runCatching {
        android.location.Geocoder(ctx).getFromLocationName(query, 6)?.map { a ->
            Hit((0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }.ifBlank { a.featureName ?: query }, a.latitude, a.longitude)
        }
    }.getOrNull() ?: emptyList()
}

/**
 * Pick a place anywhere, not just where you stand: search a name or address, drag the map under the pin, or arrive
 * here from Google Maps' Share. Free OpenStreetMap tiles and Android's geocoder, no API key.
 */
@Composable
fun PlacePickerDialog(ui: LinkUiController, start: PlaceDraft?, onClose: () -> Unit) {
    val feedback = rememberLinkFeedback()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val here = remember { ui.hereNow() }
    val names = ui.places.map { it.name }
    var name by remember { mutableStateOf(start?.name?.takeIf { it.isNotBlank() && it.length <= 20 }
        ?: listOf("Home", "Office").firstOrNull { it !in names } ?: "") }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Geo.Hit>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val initial = start?.let { GeoPoint(it.lat, it.lon) } ?: here?.let { GeoPoint(it.first, it.second) } ?: GeoPoint(19.076, 72.8777)
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "maps"); osmdroidTileCache = File(ctx.cacheDir, "maps/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setOnTouchListener { v, e ->
                v.parent?.requestDisallowInterceptTouchEvent(e.actionMasked != android.view.MotionEvent.ACTION_UP && e.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
                false
            }
            isTilesScaledToDpi = true
            minZoomLevel = 4.0; maxZoomLevel = 19.0
            overlays.add(CopyrightOverlay(ctx))
            controller.setZoom(17.0)
            controller.setCenter(initial)
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    fun search() {
        if (query.isBlank()) return
        searching = true
        scope.launch {
            hits = withContext(Dispatchers.IO) { Geo.search(ctx, query) }
            searching = false
            hits?.singleOrNull()?.let { h -> map.controller.animateTo(GeoPoint(h.lat, h.lon)); hits = null }
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = hapticAction(onClose)) { Icon(Icons.Outlined.Close, "Close") }
                    Text("Save a place", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    label = { Text("Place or address") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }))
                hits?.let { list ->
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        if (list.isEmpty()) Muted("Nothing found. Try another name, or move the map.")
                        list.forEach { h ->
                            Text(h.label, maxLines = 2, modifier = Modifier.fillMaxWidth().clickable {
                                feedback.selection()
                                map.controller.animateTo(GeoPoint(h.lat, h.lon)); hits = null
                            }.heightIn(min = 48.dp).padding(vertical = 10.dp))
                            HorizontalDivider()
                        }
                    }
                }
                // clipped: osmdroid draws tiles past its own bounds otherwise (over the search box and buttons)
                Box(Modifier.height(280.dp).fillMaxWidth().padding(top = 8.dp).clipToBounds()) {
                    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
                    // the pin stays in the middle; move the map under it
                    Icon(Icons.Filled.Place, "Pin", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center).padding(bottom = 36.dp).size(44.dp))
                    if (here != null) SmallFloatingActionButton(onClick = hapticAction({ map.controller.animateTo(GeoPoint(here.first, here.second)) }),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Outlined.MyLocation, "My location") }
                }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Muted("Move the map so the pin sits on the place. Or in Google Maps: tap a place → Share → RevTale.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Home", "Office", "Gym").forEach { n -> FilterChip(name == n, hapticSelection({ name = n }), label = { Text(n) }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(name, { name = it.take(20) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.weight(1f))
                        Button(onClick = hapticAction({
                            val c = map.mapCenter
                            ui.savePlace(SavedPlace(name.trim(), c.latitude, c.longitude)); onClose()
                        }), enabled = name.isNotBlank()) { Text("Save") }
                    }
                }
            }
        }
    }
}
