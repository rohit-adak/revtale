package io.github.revtale

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Immutable UI snapshot. Missing telemetry stays missing, never a fabricated zero. */
data class LiveTelemetry(
    val speed: Int? = null, val rpm: Int? = null, val gear: Int? = null,
    val fuel: Int? = null, val volts: Double? = null, val temperature: Int? = null,
    val odometer: Double? = null, val trip: Double? = null,
    /** Riding mode ([RideModes]), from 5A11 byte 18. */
    val mode: Int? = null
) {
    val moving get() = (speed ?: 0) > 0
    companion object {
        fun decode(frames: Map<String, String>): LiveTelemetry {
            fun frame(id: String): ByteArray? = runCatching {
                val h = frames[id] ?: return@runCatching null
                if (h.length != Protocol.FRAME_LEN * 2) return@runCatching null
                ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }.getOrNull()
            val a = frame("5A10"); val b = frame("5A11"); val c = frame("5A18"); val d = frame("5A19")
            return LiveTelemetry(
                a?.let { Protocol.u(it, 3) }, a?.let { Protocol.u(it, 17, 2) },
                b?.let { Protocol.gear(Protocol.u(it, 6)) }, a?.let { Protocol.u(it, 7) },
                c?.let { Protocol.u(it, 12) / 10.0 }, c?.let { Protocol.u(it, 9) },
                a?.let { Protocol.u(it, 4, 3) / 10.0 },
                d?.let { Protocol.u(it, 7, 3) + Protocol.u(it, 10) / 10.0 },
                b?.let { RideModes.of(Protocol.u(it, 18)) }
            )
        }
    }
}

data class LinkSnapshot(
    val live: LiveTelemetry = LiveTelemetry(), val connected: Boolean = false,
    val waiting: Boolean = false, val scanning: Boolean = false, val session: Boolean = false,
    val bikeAddress: String? = null, val bikeName: String = "Your bike",
    val devices: List<BleService.Dev> = emptyList(), val linkOn: Boolean = false,
    val unread: Int = 0, val nav: String = "", val recordRides: Boolean = true,
    /** What the bike last reported (fuel bars, odometer, battery) and when; for the Live tab while it's off. */
    val lastKnown: LastKnown? = null,
    /** Google Maps' own estimate for the route running now (live traffic): minutes and metres left. */
    val navMinutes: Int? = null, val navLeftM: Int? = null, val navDest: String? = null,
    val autoConnect: Boolean = true, val autoLink: Boolean = true, val detailedLog: Boolean = false,
    val mapsToCluster: Boolean = true,
    /** "system" (follow the phone), "dark" or "light". */
    val theme: String = "system",
    /** Developer status tiles (label → value); empty while the service is off. */
    val devStatus: List<Pair<String, String>> = emptyList(), val devSelected: String? = null, val tail: List<String> = emptyList(),
    val decoded: Map<String, String> = emptyMap(), val buttons: List<String> = emptyList(),
    val ride: RideNow? = null, val rideFile: File? = null,
    val heading: Float? = null, val compassOnCluster: Boolean = true,
    /** Simulator running instead of a real bike; [cluster] is what the app has sent to the (virtual) cluster. */
    val simulating: Boolean = false, val cluster: ClusterView? = null, val simAuto: Boolean = false, val simTarget: Int = 0,
    /** A fill-up the app noticed from the fuel bars, waiting for the rider to say what it cost. */
    val refuel: PendingRefuel? = null, val fuelPrice: Double? = null
)

/** The bike's last reported values, kept for when it's switched off. */
data class LastKnown(val fuel: Int?, val odometer: Double?, val volts: Double?, val atMs: Long)

/** The ride in progress, updated live while connected. */
data class RideNow(val distanceKm: Double, val elapsedS: Long, val movingS: Double, val topSpeed: Int)

enum class StartAction { FIND, CONNECT, AUTO, SIMULATE }

/** UI-only adapter: Bluetooth/protocol behavior remains in the existing service. */
class LinkUiController(
    private val activity: ComponentActivity, private val scope: CoroutineScope,
    private val launchPermissions: (Array<String>) -> Unit
) {
    val prefs = BleService.prefs(activity)
    val context: android.content.Context get() = activity
    var state by mutableStateOf(LinkSnapshot()); private set
    var notice by mutableStateOf<String?>(null)
    /** A notification / widget asked for a page ([Open]); [LinkApp] goes there and clears it. */
    var openRequest by mutableStateOf<String?>(null)
    /** Bumped to pull the Live drawer up. */
    var drawerPull by mutableStateOf(0)
    var permissionExplanation by mutableStateOf(false)
    var finding by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var developerVisible = false
    private var pending = StartAction.CONNECT
    private var demoActive = false
    private var connectedAt = 0L
    private var wasConnected = false
    private var lastLog: Uri? = null
    private var autoAttempted = false
    private val corePermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)

    init { refresh() }

    fun resume() {
        if (finding) BleService.instance?.startScan()
        if (!autoAttempted && prefs.getString("bike", null) != null &&
            prefs.getBoolean("autoConnect", true) && corePermissions.all(::granted) && !BikePresence.on(activity)) {
            autoAttempted = true
            start(StartAction.AUTO)
        }
    }

    fun pause() {
        stopDemo()
        if (finding) BleService.instance?.stopScan()
    }

    fun refresh() {
        val s = BleService.instance
        val connected = s?.gattState == "connected"
        if (connected && !wasConnected) connectedAt = System.currentTimeMillis()
        wasConnected = connected
        val frames = if (connected && s?.linkKey != null) s.lastDecoded.toMap() else emptyMap()
        val live = LiveTelemetry.decode(frames)
        if (live.moving) {
            stopDemo()
            if (finding) cancelFinding()
            permissionExplanation = false
        }
        if (connected && live.speed == 0 && !s.linkOn &&
            prefs.getBoolean("autoLink", true) && System.currentTimeMillis() - connectedAt > 3000 &&
            s.selected == prefs.getString("bike", null)) {
            s.startPhoneLink(prefs.getString("name", "RIDER") ?: "RIDER")
        }
        state = LinkSnapshot(
            live = live, connected = connected,
            waiting = s?.sessionOn == true && (s.autoMode || s.gattState == "connecting"),
            scanning = s?.scanning == true, session = s?.sessionOn == true,
            bikeAddress = prefs.getString("bike", null),
            bikeName = prefs.getString("bikeName", null) ?: "Your bike",
            devices = s?.devices?.values?.map { it.copy() }?.sortedByDescending { it.rssi } ?: emptyList(),
            linkOn = s?.linkOn == true, unread = s?.unreadSms ?: 0,
            nav = s?.lastNavInfo?.instruction ?: "",
            lastKnown = Away.lastSeen(activity)?.let { b ->
                LastKnown(b.fuelBars.takeIf { it >= 0 }, b.odoKm, prefs.getFloat("lastVolts", -1f).takeIf { it > 0 }?.toDouble(), b.atMs)
            },
            navMinutes = s?.lastNavInfo?.minutesLeft, navLeftM = s?.lastNavInfo?.distLeftM,
            navDest = s?.lastNavInfo?.let { prefs.getString("navDestName", null) },
            recordRides = prefs.getBoolean("recordRides", true), autoConnect = prefs.getBoolean("autoConnect", true),
            autoLink = prefs.getBoolean("autoLink", true), detailedLog = prefs.getBoolean("detailedLog", false),
            mapsToCluster = prefs.getBoolean("mapsToCluster", true), theme = prefs.getString("theme", null)
                // before the system option: an explicit "dark theme off" meant light
                ?: if (prefs.contains("darkTheme") && !prefs.getBoolean("darkTheme", true)) "light" else "system",
            devStatus = if (s == null) emptyList() else listOf(
                "Link" to s.gattState, "XOR key" to (s.linkKey?.let { "%02X".format(it) } ?: "learning"),
                "Packets" to "${s.notifyCount.get()}", "Link frames" to "${s.linkFrames.get()}",
                "MTU" to "${s.mtu}", "Phone link" to if (s.linkOn) "on" else "off"),
            devSelected = s?.selected,
            tail = if (developerVisible) s?.tail(40) ?: emptyList() else emptyList(),
            decoded = if (developerVisible) frames else emptyMap(),
            buttons = if (developerVisible) s?.buttonChanges(10) ?: emptyList() else emptyList(),
            ride = s?.liveRide?.takeIf { connected }?.let { RideNow(it.distKm, (it.lastMs - it.startMs) / 1000, it.movingS, it.top) },
            rideFile = s?.rideFile?.takeIf { connected },
            heading = s?.heading, compassOnCluster = prefs.getBoolean("compassOnCluster", true),
            simulating = s?.simulating == true, cluster = s?.takeIf { it.simulating }?.virtualCluster?.view,
            simAuto = s?.simBike?.auto ?: false, simTarget = s?.simBike?.targetKmh ?: 0,
            refuel = s?.pendingRefuel() ?: PendingRefuel.fromPref(prefs.getString("pendingRefuel", null)),
            fuelPrice = prefs.getFloat("fuelPrice", 0f).takeIf { it > 0 }?.toDouble()
        )
    }

    private fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun parked(): Boolean {
        val s = BleService.instance
        val live = if (s?.gattState == "connected") LiveTelemetry.decode(s.lastDecoded.toMap()) else LiveTelemetry()
        // the emulator is for trying things at a desk, so nothing waits for it to "stop"
        if (live.moving && s?.simulating != true) { notice = "Available when your bike is stopped."; return false }
        return true
    }

    fun requestStart(action: StartAction) {
        if (!parked() || busy) return
        pending = action
        if (corePermissions.any { !granted(it) } ||
            (!granted(Manifest.permission.POST_NOTIFICATIONS) && !prefs.getBoolean("askedNotifications", false))) {
            permissionExplanation = true
        } else start(action)
    }

    fun askPermissions() {
        permissionExplanation = false
        prefs.edit().putBoolean("askedNotifications", true).apply()
        launchPermissions((corePermissions + Manifest.permission.ACCESS_COARSE_LOCATION +
            Manifest.permission.POST_NOTIFICATIONS).filter { !granted(it) }.toTypedArray())
    }

    /** Calls on the cluster: caller number + contact name, decline with i, text back. */
    val callPermissions = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS)

    fun callPermissionsMissing() = callPermissions.filter { !granted(it) }

    private var askingCalls = false

    fun askCallPermissions() {
        askingCalls = true
        launchPermissions(callPermissionsMissing().toTypedArray())
    }

    fun setText(key: String, value: String) { prefs.edit().putString(key, value).apply() }

    fun setTheme(mode: String) { prefs.edit().putString("theme", mode).apply(); refresh() }

    /** Developer options are hidden until the version line is tapped 7 times (so a shared app looks plain). */
    var devUnlocked by mutableStateOf(prefs.getBoolean("devUnlocked", false)); private set
    fun unlockDeveloper() { prefs.edit().putBoolean("devUnlocked", true).apply(); devUnlocked = true }

    // ---- app updates (GitHub releases) ---------------------------------------------------------------------------

    val appVersion: String = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"
    /** A newer release, waiting for the rider; null = none known. */
    var update by mutableStateOf<UpdateInfo?>(null); private set
    /** Download progress 0..1 while updating; null otherwise. */
    var updateProgress by mutableStateOf<Float?>(null); private set
    var updateChecking by mutableStateOf(false); private set
    var updateLater by mutableStateOf(false)

    /** Quietly on app start (at most every 6 h); [manual] = the Settings button, which always checks and reports. */
    fun checkForUpdate(manual: Boolean = false) {
        // what the background check already found
        if (update == null) Updater.found(activity)?.takeIf { Versions.isNewer(it.version, appVersion) }?.let { update = it }
        val last = prefs.getLong("updateCheckedAt", 0)
        if (!manual && System.currentTimeMillis() - last < 6 * 3600_000L) return
        if (updateChecking) return
        updateChecking = true
        scope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updater.check(appVersion) } }
                Updater.remember(activity, result)
                val found = result.getOrThrow()
                update = found; updateLater = false
                if (manual && found == null) notice = "You're on the latest version ($appVersion)."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (manual) notice = e.message ?: "Couldn't check for updates." }
            finally { updateChecking = false }
        }
    }

    fun installUpdate() {
        val u = update ?: return
        if (!parked() || updateProgress != null) return
        updateProgress = 0f
        scope.launch {
            try {
                val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Updater.download(activity, u) { p -> scope.launch { updateProgress = p } }
                }
                Updater.install(activity, apk)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Update download failed." }
            finally { updateProgress = null }
        }
    }

    fun onPermissionsResult() {
        // back from the call permissions: nothing to connect
        if (askingCalls) {
            askingCalls = false
            if (callPermissionsMissing().isNotEmpty()) notice = "Some call permissions are off; the caller shows as a number or not at all."
            refresh(); return
        }
        if (corePermissions.all(::granted)) start(pending)
        else notice = "Allow Nearby devices and precise Location in app permissions, then try again."
    }

    private fun start(action: StartAction) {
        if (!parked() || busy) return
        val adapter = activity.getSystemService(BluetoothManager::class.java)?.adapter
        if (action == StartAction.SIMULATE) { startSimulator(); return }
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            notice = "Turn on Bluetooth, then try again."; return
        }
        if (action != StartAction.FIND && prefs.getString("bike", null) == null) {
            requestStart(StartAction.FIND); return
        }
        busy = true
        scope.launch {
            try {
                ContextCompat.startForegroundService(activity, Intent(activity, BleService::class.java))
                var service = BleService.instance
                for (attempt in 0 until 40) {
                    if (service != null) break
                    delay(100); service = BleService.instance
                }
                val s = service ?: error("Bike service did not start. Try again.")
                if (!parked()) return@launch
                s.startSession(scan = false)
                when (action) {
                    StartAction.FIND -> { finding = true; s.startScan() }
                    StartAction.AUTO -> { s.optAutoReconnect = true; s.autoStart() }
                    StartAction.SIMULATE -> {}
                    StartAction.CONNECT -> {
                        val address = prefs.getString("bike", null) ?: return@launch
                        s.optAutoReconnect = prefs.getBoolean("autoConnect", true)
                        s.autoMode = s.optAutoReconnect
                        s.select(address); s.connect()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Could not start Bluetooth. Try again." }
            finally { busy = false; refresh() }
        }
    }

    fun chooseBike(device: BleService.Dev) {
        if (!parked() || busy) return
        val s = BleService.instance ?: run { notice = "Tap Find my bike to restart scanning."; return }
        // Called ONLY by the explicit confirmation dialog, never by a scan callback.
        busy = true
        scope.launch {
            try {
                if (s.selected != device.addr && !awaitDisconnect(s)) return@launch
                if (!parked()) return@launch
                prefs.edit().putString("bike", device.addr).putString("bikeName", device.name ?: device.addr).apply()
                Bikes.add(activity, SavedBike(device.addr, device.name ?: device.addr)); reloadBikes()
                s.stopScan(); finding = false
                s.optAutoReconnect = prefs.getBoolean("autoConnect", true)
                s.autoMode = s.optAutoReconnect
                s.select(device.addr); s.connect()
                notice = "Check your cluster and enter its passkey in Android's pairing dialog."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Could not connect: ${e.message}" }
            finally { busy = false; refresh() }
        }
    }

    /** Developer → Emulator: a pretend bike in place of the real one (same permissions as a real connection). */
    fun startSimulator() {
        if (busy) return
        if (corePermissions.any { !granted(it) }) { pending = StartAction.SIMULATE; permissionExplanation = true; return }
        busy = true
        scope.launch {
            try {
                ContextCompat.startForegroundService(activity, Intent(activity, BleService::class.java))
                var service = BleService.instance
                for (attempt in 0 until 40) {
                    if (service != null) break
                    delay(100); service = BleService.instance
                }
                val s = service ?: error("Bike service did not start. Try again.")
                finding = false
                notice = s.startSimulation()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Could not start the simulator." }
            finally { busy = false; refresh() }
        }
    }

    fun stopSimulator() { BleService.instance?.stopSimulation(); refresh() }

    /** Simulator controls; these change the pretend bike only, so they work while it "moves". */
    fun simulate(change: (SimBike) -> Unit) { BleService.instance?.takeIf { it.simulating }?.let { change(it.simBike) }; refresh() }

    fun cancelFinding() { finding = false; BleService.instance?.stopScan(); state = state.copy(scanning = false) }

    fun disconnect() {
        if (!parked()) return
        stopDemo()
        BleService.instance?.let { it.autoMode = false; it.disconnect() }
        refresh()
    }

    fun forgetBike() {
        if (!parked() || busy) return
        stopDemo()
        busy = true
        scope.launch {
            try {
                val s = BleService.instance
                if (s != null && !awaitDisconnect(s)) return@launch
                s?.stopSession()
                prefs.getString("bike", null)?.let { Bikes.remove(activity, it) }
                if (BikePresence.on(activity)) { BikePresence.disable(activity); presenceOn = false }
                prefs.edit().remove("bike").remove("bikeName").apply()
                // another saved bike takes over
                Bikes.all(activity).firstOrNull()?.let { prefs.edit().putString("bike", it.addr).putString("bikeName", it.name).apply() }
                reloadBikes()
                activity.stopService(Intent(activity, BleService::class.java))
                finding = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Could not forget the bike: ${e.message}" }
            finally { busy = false; refresh() }
        }
    }

    /** Let the existing GATT callback close its ride writer before ending/replacing a session. */
    private suspend fun awaitDisconnect(s: BleService): Boolean {
        s.autoMode = false
        s.optAutoReconnect = false
        s.stopPhoneLink()
        s.disconnect()
        repeat(50) {
            if (s.gattState != "connected" && s.gattState != "connecting") {
                delay(200)
                return true
            }
            delay(100)
        }
        notice = "Waiting for Bluetooth to disconnect. Try again in a moment."
        return false
    }

    fun setPreference(key: String, value: Boolean) {
        if (!parked()) return
        prefs.edit().putBoolean(key, value).apply()
        when (key) {
            "autoConnect" -> {
                BleService.instance?.let { s ->
                    s.optAutoReconnect = value; s.autoMode = value
                    if (!value && s.gattState != "connected") s.disconnect()
                }
                if (value) requestStart(StartAction.AUTO)
            }
            "autoLink" -> if (!value) BleService.instance?.stopPhoneLink()
            "mapsToCluster" -> if (!value) BleService.instance?.onNavEnded()
            "recordRides", "detailedLog" -> notice = "Saved. Location and sensor changes take effect on your next connection."
        }
        refresh()
    }

    fun saveName(name: String) {
        if (!parked()) return
        val clean = Protocol.clusterText(name).take(17).ifBlank { "RIDER" }
        prefs.edit().putString("name", clean).apply()
        val s = BleService.instance
        s?.linkName = clean
        if (state.connected) bikeCommand { it.sendTextSlot(0x52, clean) } else notice = "Rider name saved."
    }

    fun bikeCommand(action: (BleService) -> String) {
        if (!parked()) return
        val s = BleService.instance
        if (s == null || s.gattState != "connected" || (!s.simulating && s.selected != prefs.getString("bike", null))) {
            notice = "Connect to your saved bike first."; return
        }
        if (s.linkKey == null || (!s.simulating && LiveTelemetry.decode(s.lastDecoded.toMap()).speed != 0)) {
            notice = "Waiting for the bike to report that it is stopped."; return
        }
        notice = runCatching { action(s) }.getOrElse { "Could not send: ${it.message}" }
        refresh()
    }

    fun navDemo() = bikeCommand { demoActive = true; it.navDemo() }
    fun arrow(code: Int) = bikeCommand { demoActive = true; it.showArrowCode(code) }
    fun stopDemo() {
        if (demoActive) { BleService.instance?.stopNavigation(); demoActive = false }
    }

    fun openSettings(action: String, packageUri: Boolean = false) {
        if (!parked()) return
        runCatching { activity.startActivity(Intent(action).apply {
            if (packageUri) data = Uri.parse("package:${activity.packageName}")
        }) }.onFailure { notice = "This settings page is unavailable on this phone." }
    }

    fun batteryHelp() {
        val pm = activity.getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) notice = "RevTale already has unrestricted battery access."
        else openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true)
    }

    fun saveLog() {
        if (!parked() || busy) return
        val s = BleService.instance ?: run { notice = "No session to save."; return }
        busy = true
        scope.launch {
            try {
                if (!awaitDisconnect(s)) return@launch
                s.stopSession()
                lastLog = withContext(Dispatchers.IO) { s.log.saveToDownloads() }
                notice = "Log saved to Downloads/RevTale."
                activity.stopService(Intent(activity, BleService::class.java))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Could not save log: ${e.message}" }
            finally { busy = false; refresh() }
        }
    }

    /** The calibration as text, to send to whoever collects bike profiles. */
    fun shareCalibration(c: BikeCalibration) {
        runCatching {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Bike profile: ${c.bikeName}")
                putExtra(Intent.EXTRA_TEXT, c.json())
            }, "Share bike profile"))
        }
    }

    fun shareLog() {
        if (!parked()) return
        val uri = lastLog ?: run { notice = "End and save a log first."; return }
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share session log"))
    }

    // ---- fuel log: fill-ups the rider confirmed, one CSV line each, on this phone only ----

    private val fuelFile get() = File(activity.getExternalFilesDir(null), "fuel.csv")

    suspend fun fills(): List<FillUp> = withContext(Dispatchers.IO) {
        if (!fuelFile.exists()) emptyList() else fuelFile.readLines().drop(1).mapNotNull(FillUp::parse)
    }

    /** Odometer to file a fill-up against: live from the bike, else the last one it reported. */
    fun odometerNow(): Double? = state.live.odometer ?: prefs.getFloat("lastOdo", 0f).takeIf { it > 0 }?.toDouble()

    /** Save a fill-up from the dialog: [value] is liters, or the amount paid when [isAmount]. */
    fun saveFill(value: Double, isAmount: Boolean, pricePerL: Double, odoKm: Double, refuel: PendingRefuel?): Boolean {
        val liters = FillUp.liters(value, isAmount, pricePerL)
        if (liters == null || pricePerL <= 0) { notice = "Enter the price per liter and what you filled."; return false }
        prefs.edit().putFloat("fuelPrice", pricePerL.toFloat()).apply()
        if (refuel?.simulated == true) {
            BleService.instance?.clearRefuel(); notice = "Emulator fill-up: ${decimal(liters, 2)} L (not saved)."; refresh(); return true
        }
        val fill = FillUp(refuel?.tMs ?: System.currentTimeMillis(), odoKm, liters, pricePerL, refuel?.barsFrom ?: -1, refuel?.barsTo ?: -1)
        runCatching {
            val f = fuelFile
            if (!f.exists()) f.writeText(FillUp.CSV_HEADER + "\n")
            f.appendText(fill.csv() + "\n")
        }.onFailure { notice = "Could not save the fill-up: ${it.message}"; return false }
        if (refuel != null) dismissRefuel()
        notice = "Fill-up saved: ${decimal(liters, 2)} L."
        refresh()
        return true
    }

    /**
     * A fill-up happened but nobody knows how much (a friend filled it). Logged with 0 L as a marker: the km/l of the
     * next tank then starts from here instead of being spread over two tanks.
     */
    fun skipFill(refuel: PendingRefuel) {
        if (!refuel.simulated) runCatching {
            val f = fuelFile
            if (!f.exists()) f.writeText(FillUp.CSV_HEADER + "\n")
            val price = prefs.getFloat("fuelPrice", 0f).toDouble()
            f.appendText(FillUp(refuel.tMs, refuel.odoKm, 0.0, price, refuel.barsFrom, refuel.barsTo).csv() + "\n")
        }
        dismissRefuel()
        notice = "Skipped this fill-up. Mileage starts fresh from here."
    }

    fun dismissRefuel() {
        BleService.instance?.clearRefuel() ?: prefs.edit().remove("pendingRefuel").apply()
        refresh()
    }

    // With the service off, read the same app-private directory to keep ride history available.
    // The ride in progress is included (newest first); it is flushed to disk every 2 s.
    suspend fun rides(): List<File> = withContext(Dispatchers.IO) {
        val service = BleService.instance
        service?.rides() ?: File(activity.getExternalFilesDir(null), "rides").listFiles()
            ?.filter { it.extension == "csv" }?.sortedByDescending { it.name } ?: emptyList()
    }

    fun isLive(file: File) = state.connected && BleService.instance?.rideFile == file

    /** Every saved ride together, plus a lighter copy of all samples for the overview map and place names. */
    suspend fun loadOverview(): OverviewData = withContext(Dispatchers.IO) {
        val all = rides().map { f -> f.useLines { l -> l.drop(1).mapNotNull(Sample::parse).toList() } }
        val o = RideOverviews.build(all)
        // the map only needs every 3rd point
        val mapSamples = all.flatMap { r -> r.sortedBy { it.tMs }.filterIndexed { i, _ -> i % 3 == 0 } }.sortedBy { it.tMs }
        val heats = all.mapNotNull { r -> r.minOfOrNull { it.tMs }?.let { t -> Heat.of(r)?.let { t to it } } }.sortedByDescending { it.first }
        val hotSpots = heats.flatMap { it.second.events }.filter { !it.lat.isNaN() }.sortedByDescending { it.startMs }.take(6).map { it.lat to it.lon }
        val names = (o.places.map { it.lat to it.lon } + listOfNotNull(o.parked?.let { it.lat to it.lon }) + hotSpots)
            .associateWith { (lat, lon) -> placeName(lat, lon) }
        OverviewData(o, mapSamples, names, heats)
    }

    /** "MG Road, Bengaluru" from Android's geocoder; the coordinates when it can't tell (offline). */
    @Suppress("DEPRECATION")
    private fun placeName(lat: Double, lon: Double): String = runCatching {
        android.location.Geocoder(activity).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
            // featureName is often a plus code ("8V65+M8J") or a house number: only a street name is worth showing
            listOfNotNull(a.thoroughfare, a.subLocality ?: a.locality).distinct().joinToString(", ").ifBlank { null }
        }
    }.getOrNull() ?: "%.4f, %.4f".format(java.util.Locale.US, lat, lon)

    suspend fun loadRide(file: File): RideData = withContext(Dispatchers.IO) {
        val samples = file.useLines { lines -> lines.drop(1).mapNotNull(Sample::parse).toList() }.sortedBy { it.tMs }
        val summary = BleService.instance?.loadRide(file) ?: RideStats.summarize(samples)
        val insights = RideStats.insights(samples, summary.gears)
        RideData(file, summary, samples, insights, RideScores.of(summary, insights), Heat.of(samples))
    }

    // ---- extras: places, weather, parking, backup, sharing, reports, bikes ------------------------------------------

    var actions: ActivityActions? = null

    /** First-run guide done (or skipped); riders who had a bike before the guide existed never see it. */
    var onboarded by mutableStateOf(prefs.getBoolean("onboarded", prefs.getString("bike", null) != null)); private set
    fun finishOnboarding() { prefs.edit().putBoolean("onboarded", true).apply(); onboarded = true }

    /** Calibrated once? Until then a card on Live says so; "not now" shrinks it to a tag. */
    var calibrated by mutableStateOf(Calibration.load(activity) != null); private set
    var calibrationNudge by mutableStateOf(!prefs.getBoolean("calibrationNudgeDismissed", false)); private set
    fun calibrationSaved() { calibrated = true; calibrationNudge = false }
    fun dismissCalibrationNudge() {
        prefs.edit().putBoolean("calibrationNudgeDismissed", true).apply(); calibrationNudge = false
        notice = "Any time: tap the red tag, or Settings → Calibrate your bike"
    }

    var places by mutableStateOf(Places.load(activity)); private set

    fun savePlace(p: SavedPlace) {
        places = Places.with(places, p); Places.save(activity, places)
        notice = "Saved ${p.label}."; BikeWidget.refresh(activity)
    }

    fun removePlace(p: SavedPlace) {
        places = places.filter { it != p }; Places.save(activity, places); BikeWidget.refresh(activity)
    }

    fun goTo(p: SavedPlace, usualMin: Int? = null) { if (parked()) Places.navigate(activity, p, usualMin) }

    fun previewRoute(p: SavedPlace) = Places.preview(activity, p)

    /** A place shared from Google Maps, waiting for the rider to name it and save it. */
    var placeDraft by mutableStateOf<PlaceDraft?>(null)

    fun onSharedText(text: String) {
        if (MapsLink.url(text) == null && text.isBlank()) return
        notice = "Opening the shared place…"
        scope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { MapsLink.resolve(activity, text) }.getOrNull() }
            if (d == null) notice = "Couldn't find where that link points. Search for it in Ride to → Add a place."
            else { notice = null; placeDraft = d }
        }
    }

    private var overviewCache: RideOverview? = null

    /** Before starting: road distance, empty-road time and your own usual time for this trip (from your rides). */
    suspend fun routePreview(p: SavedPlace): RoutePreview? = withContext(Dispatchers.IO) {
        val (lat, lon) = hereNow() ?: return@withContext null
        val road = RouteInfo.fetch(lat, lon, p.lat, p.lon)
        val o = overviewCache ?: runCatching {
            RideOverviews.build(rides().map { f -> f.useLines { l -> l.drop(1).mapNotNull(Sample::parse).toList() } })
        }.getOrNull()?.also { overviewCache = it }
        val u = o?.let { RouteInfo.usual(it.routes, lat, lon, p.lat, p.lon) }
        RoutePreview(road?.first, road?.second, u?.avgMinutes?.let { kotlin.math.round(it).toInt() }, u?.count ?: 0)
    }

    /** The phone's latest fix (ride GPS, else what Android last knew), for "save this spot"; null when location is off. */
    @android.annotation.SuppressLint("MissingPermission")
    fun hereNow(): Pair<Double, Double>? = runCatching {
        BleService.instance?.lastLocation?.let { return it.latitude to it.longitude }
        val lm = activity.getSystemService(android.location.LocationManager::class.java)
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.FUSED_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER).firstNotNullOfOrNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            ?.let { it.latitude to it.longitude }
    }.getOrNull()

    var weatherKey by mutableStateOf(Weather.key(activity) ?: ""); private set
    fun saveWeatherKey(k: String) { weatherKey = k.trim(); prefs.edit().putString("weatherKey", weatherKey).apply() }

    /** Weather at a spot (Weather Union with the rider's key, Open-Meteo where it has no station). */
    suspend fun weatherAt(lat: Double, lon: Double): Result<WeatherNow?> = withContext(Dispatchers.IO) {
        runCatching { Weather.fetch(weatherKey.ifBlank { null }, lat, lon) }
    }

    // rides without this phone
    var awayTrips by mutableStateOf(Away.trips(activity)); private set
    fun reloadAway() { awayTrips = Away.trips(activity) }
    fun markAwaySeen() { Away.markSeen(activity); reloadAway(); BikeWidget.refresh(activity) }

    // parking
    var parkedSpot by mutableStateOf(Parking.load(activity)); private set
    fun reloadParking() { parkedSpot = Parking.load(activity) }
    fun setParkingNote(note: String) { Parking.setNote(activity, note); reloadParking(); BikeWidget.refresh(activity) }

    /** "I parked here" by hand (no ride recorded, or a different spot). */
    fun parkHere() {
        val (lat, lon) = hereNow() ?: run { notice = "No location yet. Turn on location and try again."; return }
        Parking.save(activity, lat, lon, System.currentTimeMillis())
        reloadParking()
        scope.launch {
            withContext(Dispatchers.IO) { Parking.setPlace(activity, Parking.streetName(activity, lat, lon)) }
            reloadParking(); BikeWidget.refresh(activity)
        }
    }

    private var pendingPhoto: File? = null

    fun takeParkingPhoto() {
        val dir = File(activity.filesDir, "parking").apply { mkdirs() }
        val f = File(dir, "spot-${System.currentTimeMillis()}.jpg")
        pendingPhoto = f
        actions?.takePhoto(androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", f))
    }

    fun onPhotoTaken(ok: Boolean) {
        val f = pendingPhoto ?: return
        pendingPhoto = null
        if (ok && f.exists() && f.length() > 0) {
            Parking.load(activity)?.photo?.let { runCatching { File(it).delete() } }
            Parking.setPhoto(activity, f.absolutePath); reloadParking()
        } else f.delete()
    }

    fun clearParking() {
        Parking.load(activity)?.photo?.let { runCatching { File(it).delete() } }
        prefs.edit().remove("parkedLat").remove("parkedLon").remove("parkedPlace").remove("parkedNote").remove("parkedPhoto").apply()
        reloadParking(); BikeWidget.refresh(activity)
    }

    // backup
    var lastBackup by mutableStateOf(prefs.getLong("lastBackup", 0)); private set
    fun exportBackup() = actions?.exportBackup("revtale-backup-" +
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()) + ".zip")

    fun importBackup() = actions?.importBackup()

    private fun settingsJson(): String = org.json.JSONObject().apply {
        Backup.SETTINGS.forEach { k -> prefs.all[k]?.let { v -> put(k, org.json.JSONObject().put("t", v::class.simpleName).put("v", v)) } }
    }.toString()

    fun writeBackup(uri: Uri) {
        busy = true
        scope.launch {
            try {
                val n = withContext(Dispatchers.IO) {
                    BleService.instance?.flushRide()
                    val rides = rides().map { "rides/${it.name}" to it }
                    val files = rides + listOfNotNull(fuelFile.takeIf { it.exists() }?.let { "fuel.csv" to it })
                    activity.contentResolver.openOutputStream(uri)?.use { Backup.write(it, files, settingsJson()) } ?: error("couldn't open the file")
                    rides.size
                }
                lastBackup = System.currentTimeMillis()
                prefs.edit().putLong("lastBackup", lastBackup).apply()
                notice = "Backup saved: $n rides, fuel log, places and settings."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Backup failed: ${e.message}" }
            finally { busy = false }
        }
    }

    fun readBackup(uri: Uri) {
        busy = true
        scope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    val entries = activity.contentResolver.openInputStream(uri)?.use { Backup.read(it) } ?: error("couldn't open the file")
                    if (entries.isEmpty()) error("that isn't a RevTale backup")
                    val dir = File(activity.getExternalFilesDir(null), "rides").apply { mkdirs() }
                    var n = 0
                    entries.filterKeys { it.startsWith("rides/") }.forEach { (name, bytes) ->
                        val f = File(dir, name.removePrefix("rides/"))
                        if (!f.exists()) { f.writeBytes(bytes); n++ }
                    }
                    entries["fuel.csv"]?.let { b ->
                        val mine = if (fuelFile.exists()) fuelFile.readLines().drop(1) else emptyList()
                        val merged = Backup.mergeFuel(mine, String(b).lines().drop(1))
                        fuelFile.writeText((listOf(FillUp.CSV_HEADER) + merged).joinToString("\n", postfix = "\n"))
                    }
                    entries["settings.json"]?.let { b ->
                        val j = org.json.JSONObject(String(b))
                        val e = prefs.edit()
                        for (k in j.keys()) {
                            if (k !in Backup.SETTINGS) continue
                            val o = j.getJSONObject(k)
                            when (o.optString("t")) {
                                "Boolean" -> e.putBoolean(k, o.getBoolean("v"))
                                "String" -> e.putString(k, o.getString("v"))
                                "Float" -> e.putFloat(k, o.getDouble("v").toFloat())
                                "Int" -> e.putInt(k, o.getInt("v"))
                                "Long" -> e.putLong(k, o.getLong("v"))
                            }
                        }
                        e.apply()
                    }
                    n
                }
                places = Places.load(activity); reloadBikes()
                notice = "Restored $added rides, plus the fuel log, places and settings."
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Restore failed: ${e.message}" }
            finally { busy = false }
        }
    }

    // sharing a ride
    fun shareRide(file: File, asImage: Boolean) {
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    val data = loadRide(file)
                    val dir = File(activity.cacheDir, "share").apply { mkdirs() }
                    val out: File; val type: String
                    if (asImage) {
                        out = File(dir, file.nameWithoutExtension + ".png"); type = "image/png"
                        val bmp = RideCard.render(data, prefs.getString("bikeName", null))
                        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    } else {
                        if (data.samples.none { !it.lat.isNaN() }) error("this ride has no GPS points")
                        out = File(dir, file.nameWithoutExtension + ".gpx"); type = "application/gpx+xml"
                        out.writeText(Gpx.of(data.samples, "RevTale " + file.nameWithoutExtension.removePrefix("ride-")))
                    }
                    val uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.packageName + ".files", out)
                    Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        this.type = type; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, if (asImage) "Share your ride" else "Export GPX (Strava, Komoot…)")
                }
                actions?.share(intent)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Couldn't share: ${e.message}" }
        }
    }

    // problem report
    fun sendReport(what: String, logDays: Int) {
        scope.launch {
            try {
                val i = withContext(Dispatchers.IO) { BugReport.build(activity, what, logDays) }
                actions?.share(i)
                BugReport.clearCrash(activity)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Couldn't build the report: ${e.message}" }
        }
    }

    // bikes
    var bikes by mutableStateOf(Bikes.all(activity)); private set
    fun reloadBikes() { bikes = Bikes.all(activity) }

    /** Make another saved bike the active one: disconnect, switch, then wait for that one instead. */
    fun useBike(b: SavedBike) {
        if (!parked() || busy) return
        busy = true
        scope.launch {
            var switched = false
            try {
                BleService.instance?.let { if (!awaitDisconnect(it)) return@launch }
                if (BikePresence.on(activity)) { BikePresence.disable(activity); presenceOn = false }
                prefs.edit().putString("bike", b.addr).putString("bikeName", b.name).apply()
                switched = true
                notice = "Switched to ${b.name}."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "Couldn't switch: ${e.message}" }
            finally {
                busy = false; refresh(); reloadBikes()
                if (switched && prefs.getBoolean("autoConnect", true)) requestStart(StartAction.AUTO)
            }
        }
    }

    /** The rider's own name for a bike ("Apache RTR 160 4V") instead of what it advertises over Bluetooth. */
    fun renameBike(b: SavedBike, name: String) {
        val clean = name.trim().take(30).ifBlank { return }
        Bikes.add(activity, b.copy(name = clean))
        if (b.addr == prefs.getString("bike", null)) prefs.edit().putString("bikeName", clean).apply()
        reloadBikes(); refresh(); BikeWidget.refresh(activity)
    }

    fun removeBike(b: SavedBike) {
        if (b.addr == prefs.getString("bike", null)) { forgetBike(); return }
        Bikes.remove(activity, b.addr); reloadBikes()
    }

    // no-notification mode
    var presenceOn by mutableStateOf(BikePresence.on(activity)); private set

    fun setPresence(on: Boolean) {
        val addr = prefs.getString("bike", null) ?: run { notice = "Choose your bike first."; return }
        if (!on) { BikePresence.disable(activity); presenceOn = false; notice = "The waiting notification is back (the most reliable way)."; return }
        BikePresence.enable(activity, addr) { err ->
            presenceOn = BikePresence.on(activity)
            // the waiting service (and its notification) can go now; Android wakes the app when the bike appears
            if (err == null) BleService.instance?.takeIf { it.gattState != "connected" && !it.simulating }?.let {
                it.stopSession(); activity.stopService(Intent(activity, BleService::class.java))
            }
            notice = err ?: "Done. RevTale now sleeps until your bike turns on; its notification shows only while connected."
        }
    }
}

/** Screens the activity opens for the controller (file pickers, camera, share sheet). */
interface ActivityActions {
    fun exportBackup(suggestedName: String)
    fun importBackup()
    fun takePhoto(target: Uri)
    fun share(intent: Intent)
}

data class OverviewData(val overview: RideOverview, val mapSamples: List<Sample>, val names: Map<Pair<Double, Double>, String>,
    /** Engine heat per ride (start time → heat), rides with temperature only, newest first. */
    val heats: List<Pair<Long, RideHeat>> = emptyList())

data class RideData(val file: File, val summary: RideSummary, val samples: List<Sample>, val insights: RideInsights, val score: RideScore? = null,
    val heat: RideHeat? = null)
