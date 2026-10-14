package io.github.revtale

import android.app.Activity
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.File
import kotlinx.coroutines.launch

/** A bottom-bar tab: outlined icon, filled when selected. */
private data class Tab(val id: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector)

/** Rides tab: the all-rides overview instead of one ride's file path. */
private const val OVERVIEW = "overview"

@Composable
fun LinkApp(ui: LinkUiController) {
    val state = ui.state
    // the first-run guide covers finding the bike; later, a rider without one starts on Find my bike
    var screen by rememberSaveable { mutableStateOf(if (state.bikeAddress == null && ui.onboarded) "setup" else "live") }
    var ridePath by rememberSaveable { mutableStateOf<String?>(null) }
    // a notification / widget tap: straight to its page
    LaunchedEffect(ui.openRequest) {
        val to = ui.openRequest ?: return@LaunchedEffect
        ui.openRequest = null
        if (!ui.onboarded) return@LaunchedEffect
        when (to) {
            Open.RIDES -> { screen = "rides"; ridePath = null }
            Open.OVERVIEW -> { screen = "rides"; ridePath = OVERVIEW }
            Open.LAST_RIDE -> { val newest: java.io.File? = ui.rides().firstOrNull(); screen = "rides"; ridePath = newest?.absolutePath }
            Open.SETTINGS -> { screen = "settings"; ridePath = null }
            Open.CALIBRATE -> { screen = "calibrate"; ridePath = null }
            Open.DRAWER -> { screen = "live"; ridePath = null; ui.drawerPull++ }
            else -> { screen = "live"; ridePath = null }
        }
    }
    // the emulator never locks the app: it is for trying things at a desk
    val moving = state.live.moving && !state.simulating
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val actualScreen = if (moving) "live" else screen
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (state.theme) { "dark" -> true; "light" -> false; else -> systemDark }
    // the four main tabs are pages: swipe left/right between them, or tap the bar
    val tabIds = listOf("live", "rides", "cluster", "settings")
    val pager = rememberPagerState(initialPage = tabIds.indexOf(screen).coerceAtLeast(0)) { tabIds.size }
    val scope = rememberCoroutineScope()
    // follow where the pager is heading (not each page it passes on the way), so the bar's pill goes straight there
    // a subtle tick for every tab the pages pass: a swipe to the next tab = 1, Live -> Settings = 3
    val feedback = rememberLinkFeedback()
    var lastTick by remember { mutableStateOf(pager.currentPage) }
    LaunchedEffect(pager.currentPage) {
        if (pager.currentPage != lastTick) {
            lastTick = pager.currentPage
            if (!moving) feedback.tabTick()
        }
    }
    LaunchedEffect(pager.targetPage) { if (screen in tabIds && screen != tabIds[pager.targetPage]) { screen = tabIds[pager.targetPage]; ridePath = null } }
    LaunchedEffect(screen) { tabIds.indexOf(screen).takeIf { it >= 0 && it != pager.currentPage }?.let { pager.scrollToPage(it) } }
    SideEffect {
        ui.developerVisible = actualScreen == "developer"
        (context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    LaunchedEffect(moving) {
        if (moving) { screen = "live"; ridePath = null; keyboard?.hide(); ui.notice = null; snack.currentSnackbarData?.dismiss() }
    }
    LaunchedEffect(ui.notice, moving) {
        val message = ui.notice
        if (!moving && message != null) {
            snack.showSnackbar(message)
            if (ui.notice == message) ui.notice = null
        }
    }
    BackHandler(!moving && (screen != "live" || (screen == "rides" && ridePath != null))) {
        if (screen == "rides" && ridePath != null) ridePath = null
        else { if (screen == "setup") ui.cancelFinding(); screen = if (screen == "developer" || screen == "calibrate") "settings" else "live" }
    }
    CompositionLocalProvider(LocalLinkHapticsEnabled provides !moving) {
    RacingTheme(dark) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { if (!moving) SnackbarHost(snack) },
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RacingMark(Modifier)
                    Text("REVTALE", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (moving || state.simulating) Text(if (moving) "RIDE FOCUS" else "SIMULATOR",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            bottomBar = {
                if (!moving && ui.onboarded) Column {
                    // the selected tab grows into a pill with its name; the others are just icons
                    val tabs = listOf(
                        Tab("live", "Live", Icons.Outlined.Speed, Icons.Filled.Speed),
                        Tab("rides", "Rides", Icons.Outlined.Flag, Icons.Filled.Flag),
                        Tab("cluster", "Cluster", Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
                        Tab("settings", "Settings", Icons.Outlined.Tune, Icons.Filled.Tune))
                    // a floating capsule (like Google Photos): the selected tab is a pill inside it
                    // a soft fade behind the bar so the page dims out under it and the capsule stands clear of the cards
                    val bgc = MaterialTheme.colorScheme.background
                    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to bgc.copy(alpha = 0f), 0.45f to bgc.copy(alpha = 0.85f), 1f to bgc))
                        .navigationBarsPadding().padding(bottom = 12.dp, top = 22.dp), contentAlignment = Alignment.Center) {
                    // fixed width, so the capsule never jumps when the selected name is longer or shorter
                    Surface(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceVariant, shadowElevation = 14.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .35f))) {
                    // ONE yellow selector that slides from tab to tab (measured positions, spring), behind the icons
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
                    val current = tabs.firstOrNull { actualScreen == it.id || (it.id == "settings" && actualScreen in setOf("developer", "calibrate")) }?.id
                    val target = current?.let { bounds[it] }
                    // fluid motion: the edge in the direction of travel leads, the other follows a beat later, so the
                    // selector stretches like a drop of liquid and settles back into a pill (springs, no glass effects)
                    // finger down already pulls the selector: towards another tab it stretches a third of the way
                    // there; on its own tab it squeezes in. Lifting the finger (or the tap) lets it spring on / back.
                    var pressedId by remember { mutableStateOf<String?>(null) }
                    val pressedBounds = pressedId?.let { bounds[it] }
                    // while the pages move (a half swipe, or a tap's scroll), the selector rides along with them
                    val pos = pager.currentPage + pager.currentPageOffsetFraction
                    val lo = kotlin.math.floor(pos).toInt().coerceIn(0, tabIds.lastIndex)
                    val hi = kotlin.math.ceil(pos).toInt().coerceIn(0, tabIds.lastIndex)
                    val f = (pos - lo).coerceIn(0f, 1f)
                    val bl = bounds[tabIds[lo]]; val bh = bounds[tabIds[hi]]
                    val follow = if (pager.isScrollInProgress && actualScreen in tabIds && bl != null && bh != null)
                        (bl.first + (bh.first - bl.first) * f) to (bl.second + (bh.second - bl.second) * f) else null
                    val baseL = follow?.first ?: target?.first ?: 0f
                    val baseR = baseL + (follow?.second ?: target?.second ?: 0f)
                    val squeeze = with(density) { 5.dp.toPx() }
                    val tl = when {
                        pressedBounds == null -> baseL
                        pressedId == current -> baseL + squeeze
                        pressedBounds.first < baseL -> baseL - (baseL - pressedBounds.first) * 0.33f
                        else -> baseL
                    }
                    val tr = when {
                        pressedBounds == null -> baseR
                        pressedId == current -> baseR - squeeze
                        pressedBounds.first > baseL -> baseR + (pressedBounds.first + pressedBounds.second - baseR) * 0.33f
                        else -> baseR
                    }
                    var lastLeft by remember { mutableStateOf(tl) }
                    val right = tl >= lastLeft
                    SideEffect { lastLeft = tl }
                    fun edge(leads: Boolean) = androidx.compose.animation.core.spring<Float>(
                        dampingRatio = if (leads) 0.72f else 0.85f,
                        stiffness = if (leads) 520f else 190f)
                    val left by androidx.compose.animation.core.animateFloatAsState(tl, edge(leads = !right), label = "left")
                    val rightEdge by androidx.compose.animation.core.animateFloatAsState(tr, edge(leads = right), label = "right")
                    val x = minOf(left, rightEdge)
                    val w = kotlin.math.abs(rightEdge - left)
                    Box(Modifier.padding(6.dp).height(IntrinsicSize.Min)) {
                        if (target != null) Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.toInt(), 0) }
                            .width(with(density) { w.toDp() }).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50)))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        tabs.forEach { t ->
                            val selected = t.id == current
                            val tint by androidx.compose.animation.animateColorAsState(
                                if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                tween(220), label = "icon")
                            val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            val pressed by source.collectIsPressedAsState()
                            LaunchedEffect(pressed) {
                                // the click is felt when the finger goes down, not when it lifts
                                if (pressed) { pressedId = t.id; if (!moving) feedback.press() } else if (pressedId == t.id) pressedId = null
                            }
                            val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.9f else 1f,
                                androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
                            Row(Modifier.clip(RoundedCornerShape(50))
                                .onGloballyPositioned { c -> bounds[t.id] = c.positionInParent().x to c.size.width.toFloat() }
                                .clickable(interactionSource = source, indication = null) {
                                    ui.cancelFinding(); ui.stopDemo(); ridePath = null; screen = t.id
                                    tabIds.indexOf(t.id).takeIf { it >= 0 }?.let { scope.launch { pager.animateScrollToPage(it) } }
                                }
                                .semantics { contentDescription = t.label; this.selected = selected }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                // a dot on Settings while the bike isn't calibrated
                                BadgedBox(badge = { if (t.id == "settings" && !ui.calibrated && state.bikeAddress != null) Badge() }) {
                                    Icon(if (selected) t.selectedIcon else t.icon, null, tint = tint,
                                        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
                                }
                                androidx.compose.animation.AnimatedVisibility(selected,
                                    enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(
                                        dampingRatio = 0.78f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) +
                                        androidx.compose.animation.fadeIn(tween(200, delayMillis = 80)),
                                    exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.spring(
                                        dampingRatio = 0.78f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow)) +
                                        androidx.compose.animation.fadeOut(tween(100))) {
                                    Text(t.label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                    }
                    }
                    }
                }
            }) { padding ->
            // pages run underneath the floating bar (no cut-out behind it); their lists add the bar's height at the end
            CompositionLocalProvider(LocalBottomInset provides padding.calculateBottomPadding()) {
            Box(Modifier.padding(top = padding.calculateTopPadding()).consumeWindowInsets(padding).imePadding()) {
                if (moving) LiveScreen(state, {}, {})
                else if (!ui.onboarded) OnboardingScreen(ui)
                else if (actualScreen in tabIds) HorizontalPager(pager, Modifier.fillMaxSize(),
                    // a ride's charts and map take horizontal drags of their own
                    userScrollEnabled = ridePath == null, beyondViewportPageCount = 1) { page ->
                    when (tabIds[page]) {
                        "live" -> LiveScreen(state, drawerPull = ui.drawerPull,
                            notCalibrated = !ui.calibrated && state.bikeAddress != null && !state.simulating,
                            onCalibrate = { screen = "calibrate" }, calibrationCard = ui.calibrationNudge,
                            onCalibrateLater = ui::dismissCalibrationNudge,
                            onConnect = { if (state.bikeAddress == null) screen = "setup" else if (state.connected) ui.disconnect() else ui.requestStart(StartAction.CONNECT) },
                            onRides = { screen = "rides" }, extras = { liveExtras(ui) })
                        "rides" -> when (ridePath) {
                            null -> RideListScreen(ui, onOverview = { ridePath = OVERVIEW }) { ridePath = it.absolutePath }
                            OVERVIEW -> RideOverviewScreen(ui) { ridePath = null }
                            else -> RideDetailScreen(ui, File(ridePath!!)) { ridePath = null }
                        }
                        "cluster" -> ClusterScreen(ui)
                        "settings" -> SettingsScreen(ui, { screen = "setup" }, { screen = "developer" }, { screen = "calibrate" })
                    }
                }
                else Crossfade(actualScreen, animationSpec = tween(200), label = "Page transition") { destination ->
                    when (destination) {
                        "setup" -> SetupScreen(ui, onDone = { screen = "live" })
                        "developer" -> DeveloperScreen(ui) { screen = "settings" }
                        "calibrate" -> CalibrationScreen(ui) { screen = "settings" }
                    }
                }
            }
            }
        }
        // a fill-up was noticed: ask once the bike is stopped
        var fuelAsked by remember { mutableStateOf<Long?>(null) }
        state.refuel?.takeIf { !moving && fuelAsked != it.tMs }?.let { r -> FuelDialog(ui, r) { fuelAsked = r.tMs } }
        ui.placeDraft?.takeIf { !moving }?.let { d -> PlacePickerDialog(ui, d) { ui.placeDraft = null } }
        if (ui.update != null && (!ui.updateLater || ui.updateProgress != null) && !moving && state.refuel == null) UpdateDialog(ui)
        if (ui.permissionExplanation && !moving) AlertDialog(
            onDismissRequest = { ui.permissionExplanation = false }, title = { Text("Connect with confidence") },
            text = { Text("Nearby devices lets RevTale find and connect to your bike. Precise location supports Bluetooth discovery and records your route. Notifications keep the connection visible while the app is in the background.\n\nAndroid will ask for each permission. Notifications are optional; your rides stay on this phone.") },
            confirmButton = { TextButton(onClick = hapticAction(ui::askPermissions)) { Text("Continue") } },
            dismissButton = { TextButton(onClick = hapticAction({ ui.permissionExplanation = false })) { Text("Not now") } })
    }
}
}
