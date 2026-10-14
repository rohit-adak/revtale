package io.github.revtale

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service that owns the whole test session: BLE scan -> (bond) -> GATT connect -> discover everything ->
 * read + subscribe -> log every packet, alongside phone GPS and motion sensors, into one JSONL file.
 * Writes to the bike are OFF unless explicitly unlocked from the UI.
 */
@SuppressLint("MissingPermission")
class BleService : Service() {

    companion object {
        @Volatile var instance: BleService? = null
        private const val CHANNEL = "revtale"
        private const val NOTIF_ID = 42
        /** Stopped this long (a red light is shorter), the ride notification turns into an ordinary one. */
        const val RIDE_PAUSE_MS = 90_000L
        val IDLE_KINDS = setOf("trip", "compass", "area")
        private const val CHANNEL_QUIET = "revtale_quiet"
        private const val NOTIF_FUEL = 43
        private const val NOTIF_UPDATE = Updater.NOTIF_UPDATE
        private const val ACTION_SMS_SENT = "io.github.revtale.SMS_SENT"
        private const val LINE_REFRESH_MS = 3000L
        private const val SMS_PAGE_MS = 2000L
        private const val ARRIVED_SHOW_MS = 12_000L
        const val DEFAULT_SMS = "I'm riding right now and will call you back shortly. You can text me here: " +
            "I can read it on my bike's display while riding."
        const val ACTION_AUTO = "io.github.revtale.AUTO"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun prefs(ctx: Context) = ctx.getSharedPreferences("revtale", Context.MODE_PRIVATE)

        /** Auto mode is possible once the user has chosen their bike and left the auto-connect switch on. */
        fun autoEnabled(ctx: Context) = prefs(ctx).let { it.getBoolean("autoConnect", true) && it.getString("bike", null) != null }

        /** Starts the background service that waits for the owner's bike and connects whenever it turns on. */
        fun startAuto(ctx: Context, force: Boolean = false) {
            if (!autoEnabled(ctx)) return
            // no-notification mode: Android wakes us when the bike appears (BikePresenceService), not at boot
            if (BikePresence.on(ctx) && !force) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, BleService::class.java).setAction(ACTION_AUTO))
            } catch (_: Exception) {}
        }

        fun hex(b: ByteArray?): String = b?.joinToString("") { "%02x".format(it) } ?: ""

        fun props(p: Int): String {
            val names = listOf(1 to "BROADCAST", 2 to "READ", 4 to "WRITE_NO_RESP", 8 to "WRITE",
                16 to "NOTIFY", 32 to "INDICATE", 64 to "SIGNED_WRITE", 128 to "EXTENDED")
            return names.filter { p and it.first != 0 }.joinToString("|") { it.second }
        }

        fun bondName(s: Int) = when (s) {
            BluetoothDevice.BOND_NONE -> "NONE"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            BluetoothDevice.BOND_BONDED -> "BONDED"
            else -> "?$s"
        }
    }

    data class Dev(val addr: String, var name: String?, var rssi: Int, var seen: Long, var adv: String)

    val devices = ConcurrentHashMap<String, Dev>()
    val log by lazy { SessionLog(applicationContext) }
    val notifyCount = AtomicLong()

    @Volatile var sessionOn = false
    @Volatile var selected: String? = null
    @Volatile var gattState = "idle"
    @Volatile var mtu = 0
    @Volatile var scanning = false

    // options (set from UI)
    @Volatile var optPairFirst = true
    @Volatile var optReadAll = true
    @Volatile var optSubscribeAll = true
    @Volatile var optAutoReconnect = true
    @Volatile var optMotion = true
    @Volatile var optWritesUnlocked = false

    private val tailLines = LinkedList<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var gatt: BluetoothGatt? = null
    private var wantConnected = false
    private var pendingBondConnect = false
    private var discoveryStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var receiversOn = false
    private var gpsOn = false
    private var sensorsOn = false

    // ---- logging helpers ----------------------------------------------------------------------------------------

    fun emit(src: String, ev: String, vararg kv: Pair<String, Any?>) {
        if (!sessionOn) return
        val line = log.log(src, ev, *kv)
        synchronized(tailLines) {
            tailLines.add(line)
            while (tailLines.size > 150) tailLines.removeFirst()
        }
    }

    fun tail(n: Int): List<String> = synchronized(tailLines) { tailLines.takeLast(n) }

    fun bondOfSelected(): String {
        val a = selected ?: return "-"
        return try { bondName(adapter.getRemoteDevice(a).bondState) } catch (_: Exception) { "?" }
    }

    // ---- service lifecycle --------------------------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        calibration = Calibration.load(this)
    }

    /** The rider's calibration ([CalibrationScreen]); null = the RTR 160 4V defaults. */
    @Volatile var calibration: BikeCalibration? = null
    /** While the calibration screen listens, every 5A10 / 5A11 frame also goes here. */
    @Volatile var frameListener: ((ByteArray) -> Unit)? = null
    fun reloadCalibration() { calibration = Calibration.load(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "RevTale session", NotificationManager.IMPORTANCE_LOW))
        // Android requires a notification while the service waits for the bike; this channel keeps it out of sight
        // (minimised at the bottom of the shade, no status-bar icon). Riding uses the normal one (Live Update).
        nm.createNotificationChannel(NotificationChannel(CHANNEL_QUIET, "Waiting for your bike", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        // Location type is only allowed when started from the app; from boot / background fall back to connectedDevice.
        try {
            startForeground(NOTIF_ID, notif("Ready"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: Exception) {
            startForeground(NOTIF_ID, notif("Ready"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        // intent == null means Android restarted us after killing the process (START_STICKY)
        if (intent == null || intent.action == ACTION_AUTO) {
            if (autoEnabled(this)) autoStart() else if (!sessionOn) { stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    /**
     * Background auto-connect: open a GATT connection with autoConnect=true to the saved, already-bonded bike.
     * Android then waits at low power and connects by itself the moment the bike's ignition turns on — no scan,
     * no app, no tapping. On disconnect (ignition off) the same pending connection is re-armed.
     */
    fun autoStart() {
        val addr = prefs(this).getString("bike", null) ?: return
        if (!adapter.isEnabled) return
        val dev = adapter.getRemoteDevice(addr)
        if (!sessionOn) startSession(scan = false)
        autoMode = true
        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
            // never trigger a pairing dialog from the background; the first pairing is done from the app
            emit("auto", "not_bonded", "addr" to addr)
            return
        }
        selected = addr
        wantConnected = true
        if (gattState == "connected" || gattState == "connecting") return
        emit("auto", "arm", "addr" to addr)
        openGatt(dev, true)
        scheduleIdleStop()
    }

    @Volatile var autoMode = false

    /**
     * No-notification mode: once the bike is gone (ignition off) and nothing reconnects for a while, the service ends
     * itself, taking its notification with it. Android starts it again when the bike reappears.
     */
    private val idleStop = Runnable {
        if (!BikePresence.on(this) || simulating || gattState == "connected") return@Runnable
        emit("auto", "idle_stop")
        stopSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        BikeWidget.refresh(this)
    }

    private fun scheduleIdleStop(ms: Long = 3 * 60_000L) {
        handler.removeCallbacks(idleStop)
        if (BikePresence.on(this)) handler.postDelayed(idleStop, ms)
    }

    /** Android says the bike is out of range / switched off. */
    fun presenceGone() = handler.post { scheduleIdleStop(60_000L) }

    fun bikeName(): String {
        if (simulating) return "Simulated bike"
        val a = selected ?: return "your bike"
        // the name the rider gave it ("Apache RTR 160 4V") before what it advertises over Bluetooth
        if (a == prefs(this).getString("bike", null)) prefs(this).getString("bikeName", null)?.let { return it }
        return devices[a]?.name ?: try { adapter.getRemoteDevice(a).name } catch (_: Exception) { null } ?: a
    }

    private fun notif(text: String, title: String = "RevTale", chip: String? = null): Notification =
        Notification.Builder(this, if (chip != null || gattState == "connected") CHANNEL else CHANNEL_QUIET)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(chip != null)
            .setOnlyAlertOnce(true)
            // riding: the live screen; stopped after a ride: that ride's details
            .setContentIntent(Open.pending(this, if (chip == null && liveRide != null) Open.LAST_RIDE else Open.LIVE))
            .apply {
                // Android 16 Live Update: shown in the status-bar chip and Samsung's Now Bar while a ride is live
                if (chip != null) {
                    // shown on the lock screen / AOD (Now Bar) too: only speed, gear and distance, nothing private
                    setVisibility(Notification.VISIBILITY_PUBLIC)
                    setCategory(if (navStep != null || lastNavInfo != null) Notification.CATEGORY_NAVIGATION else Notification.CATEGORY_STATUS)
                    liveRide?.let { setWhen(it.startMs); setUsesChronometer(true) }
                }
                if (chip != null && Build.VERSION.SDK_INT >= 36) {
                    // = Notification.EXTRA_REQUEST_PROMOTED_ONGOING (setRequestPromotedOngoing is only in the 36.1 SDK)
                    addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
                    setShortCriticalText(chip)
                }
            }
            .build()

    private var lastLiveNotif = 0L
    private var lastTripLine = 0L

    /** What the custom line shows when no route runs: any of "trip" (ride so far, default), "compass", "area", or "off". */
    fun idleLine(): String = prefs(this).getString("idleLine", null)
        ?: if (prefs(this).getBoolean("compassOnCluster", true)) "trip" else "off"
    fun idleKinds(): Set<String> = idleLine().split(',').map { it.trim() }.filter { it in IDLE_KINDS }.toSet()
    private var idleTurn = 0
    private var lastIdleSwap = 0L
    /** The area line ("ON NH 48") from the last geocoded fix, and where that fix was. */
    @Volatile private var areaText: String? = null
    private var areaAt: Location? = null
    private var areaAskedMs = 0L

    /** Ride details while connected (speed, gear, mode, distance, ride time, next turn); status text otherwise. */
    private fun postNotif() {
        if (!sessionOn) return
        val r = liveRide
        val n = if (gattState == "connected" && r != null && !r.riding) {
            // stopped for a while (parked, engine off): an ordinary notification the rider can swipe away
            val mins = ((r.lastMs - r.startMs) / 60000).toInt()
            notif("%.1f km · %d:%02d ride so far · tap to open".format(Locale.US, r.distKm, mins / 60, mins % 60), "Stopped")
        } else if (gattState == "connected" && r != null) {
            val mins = ((r.lastMs - r.startMs) / 60000).toInt()
            val gear = if (r.gear == 0) "N" else "${r.gear}"
            val mode = if (r.mode >= 0) " · ${RideModes.name(r.mode)}" else ""
            val turn = lastNavInfo?.let { nav ->
                NavParser.shortText(nav.maneuver).takeIf { it.isNotBlank() }?.let { t ->
                    " · " + t.lowercase().replaceFirstChar { it.uppercase() } + (nav.distToTurnM?.let { " in $it m" } ?: "")
                }
            } ?: ""
            notif("%.1f km · %d:%02d ride · top %d km/h%s".format(Locale.US, r.distKm, mins / 60, mins % 60, r.top, turn),
                "${r.speed} km/h · gear $gear$mode", "${r.speed} km/h")
        } else notif(when (gattState) {
            "connected" -> "Connected to ${bikeName()}" + if (linkOn) " · phone link ON" else ""
            "connecting" -> "Waiting for your bike"
            else -> "Not connected"
        })
        // the same words again would bring back a notification the rider swiped away
        val key = n.extras.getString(Notification.EXTRA_TITLE) + "|" + n.extras.getCharSequence(Notification.EXTRA_TEXT)
        if (key == lastNotifKey) return
        lastNotifKey = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }
    private var lastNotifKey: String? = null

    private val notifTick = object : Runnable {
        override fun run() {
            if (!sessionOn) return
            if (liveRide?.riding != true || gattState != "connected") postNotif()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onDestroy() {
        if (sessionOn) stopSession()
        instance = null
        super.onDestroy()
    }

    // ---- session ------------------------------------------------------------------------------------------------

    fun startSession(scan: Boolean = true) {
        if (sessionOn) { if (scan) startScan(); return }
        val name = "apache-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        log.open(name)
        sessionOn = true
        notifyCount.set(0)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        emit("sys", "session_start",
            "app" to "RevTale $ver", "model" to Build.MODEL, "android" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT, "tz" to java.util.TimeZone.getDefault().id,
            "bt_enabled" to adapter.isEnabled, "scan" to scan)
        registerReceivers()
        if (scan) startScan()
        handler.postDelayed(updateTick, 20_000)
        handler.postDelayed(notifTick, 1000)
    }

    /** GPS, motion sensors and a wake lock only while the bike is actually connected (saves battery while waiting). */
    // ---- ride recorder: one compact CSV per ride (speed, RPM, gear, throttle, GPS, volts) for analytics -----------

    val recordRides get() = prefs(this).getBoolean("recordRides", true)
    @Volatile var lastLocation: Location? = null
    private var rideWriter: java.io.BufferedWriter? = null
    @Volatile var rideFile: java.io.File? = null
    private var lastGear = 0
    private var lastMode = -1
    private var lastVolts = Double.NaN
    @Volatile var lastEngineC = -1; private set
    private var lastAirC = -1
    private var lastFlush = 0L

    /** Running totals of the ride in progress, for the live notification (Now Bar) and the app's live ride view. */
    class LiveRide(val startMs: Long) {
        @Volatile var distKm = 0.0
        @Volatile var movingS = 0.0
        @Volatile var top = 0
        @Volatile var speed = 0
        @Volatile var gear = 0
        @Volatile var mode = -1
        var lastMs = startMs
        /** Last time the wheels turned: the live chip (and the unswipeable notification) only while riding. */
        @Volatile var lastMovingMs = startMs
        val riding get() = speed > 0 || System.currentTimeMillis() - lastMovingMs < RIDE_PAUSE_MS
    }
    @Volatile var liveRide: LiveRide? = null

    // ---- rides without this phone: compare what the bike reports now with what it reported when we last saw it ------

    @Volatile private var bikeNow: BikeSnapshot? = null
    private var awayChecked = false
    private var awaySavedAt = 0L

    private fun watchAway(p: ByteArray, now: Long) {
        val odo = Protocol.u(p, 4, 3) / 10.0
        if (odo <= 0 || simulating) return
        val snap = BikeSnapshot(now, odo, Protocol.u(p, 7), Protocol.u(p, 10))
        bikeNow = snap
        if (!awayChecked) {
            awayChecked = true
            val trip = Away.detect(Away.lastSeen(this), snap)
            Away.remember(this, snap); awaySavedAt = now
            if (trip != null) {
                emit("away", "ridden", "km" to trip.km, "fuel_from" to trip.fuelFrom, "fuel_to" to trip.fuelTo)
                val app = applicationContext
                @SuppressLint("MissingPermission")
                val here = runCatching {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }.getOrNull()
                Thread {
                    // where it stood: the parking spot saved when this phone last left it
                    val left = Parking.load(app)?.takeIf { kotlin.math.abs(it.atMs - trip.fromMs) < 15 * 60_000L }
                        ?.let { it.place ?: "%.4f, %.4f".format(Locale.US, it.lat, it.lon) }
                    val back = here?.let { Parking.streetName(app, it.latitude, it.longitude) }
                    val full = trip.copy(leftFrom = left, backAt = back)
                    Away.add(app, full)
                    Away.notify(app, full)
                    BikeWidget.refresh(app)
                }.start()
            }
        } else if (now - awaySavedAt > 60_000) { Away.remember(this, snap); awaySavedAt = now } // survives a crash mid-ride
    }

    /** Ignition off after a ride: where the bike now stands (last GPS fix of the ride), with a quiet notification. */
    private fun rememberParking() {
        val r = liveRide ?: return
        val loc = lastLocation?.takeIf { System.currentTimeMillis() - it.time < 3 * 60_000L } ?: return
        if (simulating || r.distKm < 0.2 || !prefs(this).getBoolean("parkedAlert", true)) return
        Parking.save(this, loc.latitude, loc.longitude, System.currentTimeMillis())
        emit("ride", "parked")
        val app = applicationContext
        Thread {
            Parking.setPlace(app, Parking.streetName(app, loc.latitude, loc.longitude))
            Parking.load(app)?.let { Parking.notify(app, it) }
            BikeWidget.refresh(app)
        }.start()
    }

    fun ridesDir() = java.io.File(getExternalFilesDir(null), "rides").apply { mkdirs() }

    private fun recordSample(p: ByteArray) {
        if (p[0] != 0x5A.toByte()) return
        when (p[1].toInt() and 0xFF) {
            0x11 -> { lastGear = Protocol.gear(Protocol.u(p, 6)); lastMode = RideModes.of(Protocol.u(p, 18)); return }
            0x18 -> { lastVolts = Protocol.u(p, 12) / 10.0; lastEngineC = Protocol.u(p, 9); lastAirC = Protocol.u(p, 8); watchHeat(); return }
            0x10 -> {}
            else -> return
        }
        val now = System.currentTimeMillis()
        watchFuel(p, now)
        val loc = lastLocation?.takeIf { now - it.time < 5000 }
        watchAway(p, now)
        val s = Sample(now, Protocol.u(p, 3), Protocol.u(p, 17, 2), lastGear, Protocol.u(p, 11),
            loc?.latitude ?: Double.NaN, loc?.longitude ?: Double.NaN, lastVolts, lastMode, lastEngineC, lastAirC)
        updateLiveRide(s)
        watchTripEnd(s)
        if (!recordRides || simulating) return
        val w = rideWriter ?: try {
            val name = "ride-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
            val f = java.io.File(ridesDir(), name)
            rideFile = f
            f.bufferedWriter().also { it.write(Sample.CSV_HEADER); it.newLine(); rideWriter = it }
        } catch (_: Exception) { return }
        // flushed every 2 s so the ride in progress can be opened live in the app and survives a crash
        try { w.write(s.csv()); w.newLine(); if (now - lastFlush > 2000) { w.flush(); lastFlush = now } } catch (_: Exception) {}
    }

    private fun updateLiveRide(s: Sample) {
        val r = liveRide ?: LiveRide(s.tMs).also { liveRide = it }
        val dt = ((s.tMs - r.lastMs) / 1000.0).coerceIn(0.0, 5.0)
        r.lastMs = s.tMs
        r.distKm += s.speedKmh * dt / 3600.0
        if (s.speedKmh > 0) r.movingS += dt
        r.top = maxOf(r.top, s.speedKmh)
        r.speed = s.speedKmh; r.gear = s.gear; r.mode = s.mode
        if (s.speedKmh > 0) r.lastMovingMs = s.tMs
        if (s.tMs - lastLiveNotif >= 1000) { lastLiveNotif = s.tMs; postNotif() }
        BikeWidget.refreshWhileRiding(this)
        // no route, no call: the ride so far on the line, refreshed every 30 s
        val kinds = idleKinds()
        // several things picked (or the area): take turns every 8 s
        if ((kinds.size > 1 || "area" in kinds) && lastNavInfo == null && !callOnLine && smsWho == null && linkOn && s.tMs - lastIdleSwap >= 8_000) {
            lastIdleSwap = s.tMs
            val lines = listOfNotNull(
                ClusterLines.rideSoFar(r.distKm, r.movingS).takeIf { "trip" in kinds && r.distKm >= 0.1 },
                heading?.let { ClusterLines.heading(Compass.stable(compassSector, it).also { sec -> compassSector = sec }) }?.takeIf { "compass" in kinds },
                areaText?.takeIf { "area" in kinds })
            if (lines.isNotEmpty() && !keptLine.startsWith("ENGINE")) keepLine(lines[idleTurn++ % lines.size])
        }
        if (kinds == setOf("trip") && lastNavInfo == null && !callOnLine && smsWho == null && linkOn && s.tMs - lastTripLine >= 30_000 && r.distKm >= 0.1) {
            lastTripLine = s.tMs
            if (!keptLine.startsWith("ENGINE") && keptLine.firstOrNull()?.isDigit() != true) keepLine(ClusterLines.rideSoFar(r.distKm, r.movingS))
        }
    }

    // ---- end of a ride on the dash: engine off and standing for 10 s → the trip so far, for 10 s -----------------

    private var engineOffSince = 0L
    private var summaryAtKm = 0.0

    private fun watchTripEnd(s: Sample) {
        val r = liveRide ?: return
        if (s.rpm > 0 || s.speedKmh > 0) { engineOffSince = 0L; return }
        if (engineOffSince == 0L) { engineOffSince = s.tMs; return }
        // once per stretch of riding (a traffic-light engine stop after 300 m doesn't need a summary)
        if (s.tMs - engineOffSince < 10_000 || r.distKm - summaryAtKm < 0.5) return
        if (!prefs(this).getBoolean("rideSummaryOnCluster", true) || lastNavInfo != null || callOnLine || smsWho != null || !linkOn) return
        summaryAtKm = r.distKm
        val text = ClusterLines.trip(r.distKm, r.movingS, if (r.movingS > 0) r.distKm / (r.movingS / 3600) else 0.0)
        emit("ride", "summary_on_cluster", "text" to text)
        keepLine(text, force = true)
        handler.postDelayed({ if (keptLine == text) { keepLine(" ", force = true); resetCompass() } }, 10_000)
    }

    /** Everything recorded so far on disk (for a backup taken mid-ride). */
    fun flushRide() = handler.post { runCatching { rideWriter?.flush() } }

    // ---- engine heat: a short warning on the dash when it stays very hot ----------------------------------------

    private var veryHotSince = 0L
    private var heatShownAt = 0L

    private fun watchHeat() {
        val now = System.currentTimeMillis()
        if (lastEngineC < Heat.VERY_HOT) { veryHotSince = 0L; return }
        if (veryHotSince == 0L) { veryHotSince = now; return }
        if (now - veryHotSince < 30_000 || now - heatShownAt < 10 * 60_000L) return
        if (!prefs(this).getBoolean("heatOnCluster", true) || callOnLine || smsWho != null || !linkOn || !linked()) return
        heatShownAt = now
        val text = Heat.clusterLine(lastEngineC)
        val before = keptLine
        emit("heat", "very_hot", "engine_c" to lastEngineC, "air_c" to lastAirC)
        keepLine(text, force = true)
        handler.postDelayed({
            if (keptLine == text) {
                if (lastNavInfo != null && lastNavText.isNotEmpty()) keepLine(lastNavText, force = true)
                else { keepLine(before.takeIf { !it.startsWith("ENGINE") } ?: " ", force = true); resetCompass() }
            }
        }, 8000)
    }

    private fun closeRide() {
        engineOffSince = 0L; summaryAtKm = 0.0
        // this month's km for the widget (the Rides tab sets the exact figure when opened)
        liveRide?.takeIf { !simulating && it.distKm > 0.1 }?.let { r ->
            val key = SimpleDateFormat("yyyyMM", Locale.US).format(Date())
            val p = prefs(this)
            val km = (if (p.getString("monthKey", null) == key) p.getFloat("monthKm", 0f) else 0f) + r.distKm.toFloat()
            p.edit().putString("monthKey", key).putFloat("monthKm", km)
                .putString("lastRide", "${"%.1f".format(Locale.US, r.distKm)},${((r.lastMs - r.startMs) / 60_000)},${r.top},${r.startMs}").apply()
        }
        liveRide = null
        try { rideWriter?.close() } catch (_: Exception) {}
        rideWriter = null
        // a ride where the bike never moved is not worth keeping
        rideFile?.let { f -> if (f.readLines().drop(1).none { (Sample.parse(it)?.speedKmh ?: 0) > 0 }) f.delete() }
        rideFile = null
    }

    /** All recorded rides, newest first. */
    fun rides(): List<java.io.File> = ridesDir().listFiles { f -> f.name.endsWith(".csv") }?.sortedByDescending { it.name } ?: emptyList()

    fun loadRide(f: java.io.File): RideSummary = RideStats.summarize(f.readLines().drop(1).mapNotNull { Sample.parse(it) })

    private fun rideStarted() {
        if (detailedLog || recordRides) startGps()
        if (detailedLog) startSensors()
        if (wakeLock?.isHeld != true) wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "revtale:ride").also { it.acquire(4 * 60 * 60 * 1000L) }
    }

    private fun rideStopped() {
        if (!simulating) bikeNow?.let { Away.remember(this, it) }
        if (!simulating && (!lastVolts.isNaN() || lastEngineC > 0)) prefs(this).edit()
            .putFloat("lastVolts", if (lastVolts.isNaN()) -1f else lastVolts.toFloat()).putInt("lastEngineC", lastEngineC).apply()
        rememberParking()
        closeRide()
        stopGps()
        stopSensors()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    fun stopSession(): String? {
        if (!sessionOn) return log.file?.absolutePath
        stopCodeTest()
        stopSimulation()
        wantConnected = false
        pendingBondConnect = false
        autoMode = false
        if (navStep != null) stopNavigation()
        if (linkOn) stopPhoneLink()
        stopScan()
        stopGps()
        stopSensors()
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        emit("sys", "session_end", "lines" to log.count.get(), "notify" to notifyCount.get())
        unregisterReceivers()
        handler.removeCallbacksAndMessages(null)
        sessionOn = false
        gattState = "idle"
        log.close()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        return log.file?.absolutePath
    }

    // ---- scanning -----------------------------------------------------------------------------------------------

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val addr = r.device.address
            val name = r.scanRecord?.deviceName ?: try { r.device.name } catch (_: Exception) { null }
            val adv = hex(r.scanRecord?.bytes)
            val now = System.currentTimeMillis()
            val existing = devices[addr]
            val d = existing ?: Dev(addr, name, r.rssi, now, adv).also { devices[addr] = it }
            val nameNew = name != null && d.name != name
            val lastLogged = d.seen
            d.rssi = r.rssi
            if (name != null) d.name = name
            if (adv.isNotEmpty()) d.adv = adv
            // Only log the bike (named like one) or the selected device, never unrelated neighbours' devices.
            val interesting = Bikes.looksLikeBike(d.name) || addr == selected
            if (interesting && (existing == null || nameNew || now - lastLogged > 2000)) {
                d.seen = now
                emit("ble", "adv", "addr" to addr, "name" to d.name, "rssi" to r.rssi, "connectable" to r.isConnectable, "adv" to d.adv)
            } else if (existing != null && now - lastLogged > 2000) {
                d.seen = now
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emit("ble", "scan_failed", "code" to errorCode)
        }
    }

    fun startScan() {
        if (scanning || !sessionOn) return
        try {
            adapter.bluetoothLeScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
            scanning = true
            emit("ble", "scan_start")
        } catch (e: Exception) {
            emit("ble", "scan_error", "msg" to e.message)
        }
    }

    fun stopScan() {
        if (!scanning) return
        try { adapter.bluetoothLeScanner.stopScan(scanCb) } catch (_: Exception) {}
        scanning = false
        emit("ble", "scan_stop")
    }

    // ---- connect / bond -----------------------------------------------------------------------------------------

    fun select(addr: String) {
        selected = addr
        emit("ui", "select", "addr" to addr, "name" to devices[addr]?.name)
    }

    fun connect() {
        val addr = selected ?: return emit("ui", "connect_no_selection")
        val dev = adapter.getRemoteDevice(addr)
        stopScan()
        wantConnected = true
        emit("ui", "connect", "addr" to addr, "bond" to bondName(dev.bondState), "pair_first" to optPairFirst)
        if (optPairFirst && dev.bondState == BluetoothDevice.BOND_NONE) {
            pendingBondConnect = true
            val ok = dev.createBond()
            emit("ble", "create_bond", "started" to ok)
            // If the bond never completes, try the GATT connection anyway (it can trigger pairing itself).
            handler.postDelayed({
                if (pendingBondConnect && wantConnected) {
                    pendingBondConnect = false
                    emit("ble", "bond_timeout_connect_anyway")
                    openGatt(dev, false)
                }
            }, 90_000)
        } else {
            openGatt(dev, false)
        }
    }

    fun disconnect() {
        if (simulating) { stopSimulation(); return }
        emit("ui", "disconnect")
        wantConnected = false
        pendingBondConnect = false
        try { gatt?.disconnect() } catch (_: Exception) {}
    }

    private fun openGatt(dev: BluetoothDevice, auto: Boolean) {
        try { gatt?.close() } catch (_: Exception) {}
        gattState = "connecting"
        emit("gatt", "connect_gatt", "addr" to dev.address, "auto" to auto)
        gatt = dev.connectGatt(this, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnect() {
        val a = selected ?: return
        if (!wantConnected || !sessionOn) return
        openGatt(adapter.getRemoteDevice(a), true)
    }

    // ---- GATT operation queue (one outstanding operation at a time) ---------------------------------------------

    private val ops = ConcurrentLinkedQueue<() -> Unit>()
    private var busy = false
    private val opTimeout = Runnable {
        emit("gatt", "op_timeout")
        opDone()
    }

    private fun enqueue(op: () -> Unit) {
        handler.post { ops.add(op); pump() }
    }

    private fun pump() {
        if (busy) return
        val op = ops.poll() ?: return
        busy = true
        handler.postDelayed(opTimeout, 6000)
        try { op() } catch (e: Exception) { emit("gatt", "op_error", "msg" to e.message); opDone() }
    }

    private fun opDone() {
        handler.post {
            handler.removeCallbacks(opTimeout)
            busy = false
            pump()
        }
    }

    private fun resetOps() {
        handler.post {
            ops.clear()
            handler.removeCallbacks(opTimeout)
            busy = false
        }
    }

    private fun ascii(b: ByteArray): String? =
        if (b.isNotEmpty() && b.all { it in 0x20..0x7e }) String(b, Charsets.US_ASCII) else null

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            emit("gatt", "conn_state", "status" to status, "state" to newState)
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gattState = "connected"
                discoveryStarted = false
                resetOps()
                stopScan()
                // learn (or confirm) this bike's XOR key from its own frames — nothing is hard-coded per bike
                val known = prefs(this@BleService).getInt("key_" + g.device.address, -1)
                keyDetector = KeyDetector(preferred = known.takeIf { it >= 0 })
                linkKey = null
                bootstrapKey = known.takeIf { it >= 0 } ?: Protocol.HANDSHAKE_KEY
                compassSector = -1
                fuelWatcher = null; lastFuelMs = 0L
                awayChecked = false; bikeNow = null
                handler.removeCallbacks(idleStop)
                rideStarted()
                BikeWidget.refresh(this@BleService)
                emit("gatt", "request_mtu", "ok" to g.requestMtu(247))
                handler.postDelayed({ if (!discoveryStarted && gatt === g) startDiscovery(g) }, 2500)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gattState = "disconnected"
                mtu = 0
                resetOps()
                rideStopped()
                try { g.close() } catch (_: Exception) {}
                if (gatt === g) gatt = null
                if (wantConnected && optAutoReconnect && sessionOn) {
                    emit("gatt", "will_reconnect")
                    handler.postDelayed({ reconnect() }, 1500)
                }
                scheduleIdleStop()
                BikeWidget.refresh(this@BleService)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = m
            emit("gatt", "mtu", "mtu" to m, "status" to status)
            if (!discoveryStarted) startDiscovery(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            emit("gatt", "services_discovered", "status" to status, "count" to g.services.size)
            for (s in g.services) {
                emit("gatt", "service", "uuid" to s.uuid, "primary" to (s.type == BluetoothGattService.SERVICE_TYPE_PRIMARY))
                for (c in s.characteristics) {
                    emit("gatt", "char",
                        "service" to s.uuid, "uuid" to c.uuid, "props" to props(c.properties), "handle" to c.instanceId,
                        "descriptors" to c.descriptors.joinToString(",") { it.uuid.toString() })
                }
            }
            handler.post {
                queueInitialOps(g)
                // After a reconnect, resume the phone link automatically like the official app does.
                if (linkOn) handler.postDelayed({ if (linkOn) runStartupSequence() }, 1500)
                // Background connect: start the phone link by itself (name, clock, battery) unless the user turned it off.
                else if (prefs(this@BleService).getBoolean("autoLink", true)) handler.postDelayed({
                    if (!linkOn && gattState == "connected") startPhoneLink(prefs(this@BleService).getString("name", null) ?: "RIDER")
                }, 1500)
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            emit("gatt", "read", "uuid" to c.uuid, "hex" to hex(value), "ascii" to ascii(value), "len" to value.size, "status" to status)
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onBikeFrame(value, g.device.address, c.uuid.toString())
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            emit("gatt", "cccd_write", "char" to d.characteristic.uuid, "status" to status)
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            emit("gatt", "write_result", "uuid" to c.uuid, "status" to status)
            opDone()
        }
    }

    /** One frame from the bike (or the simulator): learn the key, decode, record. */
    private fun onBikeFrame(value: ByteArray, addr: String, uuid: String) {
        notifyCount.incrementAndGet()
        if (linkKey == null) keyDetector.offer(value)?.let { k ->
            linkKey = k
            if (!simulating) prefs(this).edit().putInt("key_" + addr, k).apply()
            emit("link", "key_detected", "key" to "%02X".format(k))
            // Handshake went out with a different key: redo it with the real one.
            if (k != bootstrapKey && linkOn) handler.post { runStartupSequence() }
        }
        val plain = linkKey?.let { Protocol.decode(value, it) }
        if (plain != null) {
            lastDecoded[hex(plain).take(4).uppercase()] = hex(plain)
            watchButtons(plain)
            recordSample(plain)
        }
        if (detailedLog) emit(if (simulating) "sim" else "gatt", "notify", "uuid" to uuid, "hex" to hex(value), "ascii" to ascii(value),
            "len" to value.size, "dec" to plain?.let { hex(it) }, "ck" to (plain != null))
    }

    private fun startDiscovery(g: BluetoothGatt) {
        discoveryStarted = true
        emit("gatt", "discover_services", "ok" to g.discoverServices())
    }

    private fun queueInitialOps(g: BluetoothGatt) {
        for (s in g.services) for (c in s.characteristics) {
            if (optReadAll && c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                enqueue {
                    if (!g.readCharacteristic(c)) { emit("gatt", "read_rejected", "uuid" to c.uuid); opDone() }
                }
            }
        }
        for (s in g.services) for (c in s.characteristics) {
            val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (optSubscribeAll && (notify || indicate)) {
                enqueue {
                    g.setCharacteristicNotification(c, true)
                    val d = c.getDescriptor(CCCD)
                    if (d == null) {
                        emit("gatt", "no_cccd", "uuid" to c.uuid); opDone()
                    } else {
                        val v = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        val rc = g.writeDescriptor(d, v)
                        if (rc != BluetoothStatusCodes.SUCCESS) { emit("gatt", "cccd_rejected", "uuid" to c.uuid, "rc" to rc); opDone() }
                    }
                }
            }
        }
    }

    // ---- optional writes (locked by default) --------------------------------------------------------------------

    fun writableChars(): List<String> {
        val g = gatt ?: return emptyList()
        val w = BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        return g.services.flatMap { s -> s.characteristics.filter { it.properties and w != 0 }.map { it.uuid.toString() } }
    }

    fun writeHex(uuid: String, hexStr: String, noResponse: Boolean): String {
        if (!optWritesUnlocked) return "Writes are locked"
        val g = gatt ?: return "Not connected"
        val clean = hexStr.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (clean.isEmpty() || clean.length % 2 != 0) return "Hex must be an even number of digits"
        val bytes = ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val c = g.services.flatMap { it.characteristics }.firstOrNull { it.uuid.toString().equals(uuid.trim(), true) }
            ?: return "Characteristic not found"
        enqueue {
            val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val rc = g.writeCharacteristic(c, bytes, type)
            emit("gatt", "write", "uuid" to c.uuid, "hex" to clean, "no_response" to noResponse, "rc" to rc)
            if (rc != BluetoothStatusCodes.SUCCESS || noResponse) opDone()
        }
        return "Queued ${bytes.size} bytes"
    }

    // ---- cluster test: rider-name frame only (display text, no vehicle control) -------------------------------

    private val apacheService: UUID = UUID.fromString("5456534D-5647-5341-5342-454E544F5251")
    private val apacheWrite: UUID = UUID.fromString("00005352-0000-1000-8000-00805f9b34fb")

    // ---- phone link: startup sequence + 2 s cyclic "mobile data", using the key learned from this bike ----------

    /** This bike's XOR key, learned from its frames after each connect (see [KeyDetector]); null until known. */
    @Volatile var linkKey: Int? = null
    private var keyDetector = KeyDetector()
    private var bootstrapKey = Protocol.HANDSHAKE_KEY
    @Volatile var linkOn = false
    @Volatile var linkName = "RIDER"
    val linkFrames = AtomicLong()

    /** Detailed logs (every bike frame, GPS, motion sensors) are for protocol research; off by default. */
    val detailedLog get() = prefs(this).getBoolean("detailedLog", false)

    fun mobileDataFrame(): ByteArray {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val signal = try {
            (getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager).signalStrength?.level ?: 3
        } catch (_: Exception) { 3 }
        val cal = java.util.Calendar.getInstance()
        return Protocol.mobileDataFrame(Protocol.PhoneStatus(
            signal = signal, batteryLevel = Protocol.batteryLevel(pct),
            hour12 = cal.get(java.util.Calendar.HOUR).let { if (it == 0) 12 else it },
            minute = cal.get(java.util.Calendar.MINUTE), second = cal.get(java.util.Calendar.SECOND),
            pm = cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.PM,
            day = cal.get(java.util.Calendar.DAY_OF_MONTH), month = cal.get(java.util.Calendar.MONTH) + 1,
            year2 = cal.get(java.util.Calendar.YEAR) % 100, unreadSms = unreadSms,
            smsStatus = if (System.currentTimeMillis() < smsStatusUntil) smsStatus else 0))
    }

    /** The owner's bike, chosen explicitly by the user. Nothing is ever written to any other device. */
    private fun ownerBike(): String? = prefs(this).getString("bike", null)

    private fun writeApache(plain: ByteArray, tag: String, encrypt: Boolean = true) {
        if (simulating) {
            // the simulated bike wakes up on the phone's handshake, like the real one
            if (plain[0] == Protocol.START_INFO.toByte() && plain[1] == Protocol.ID_RIDER_NAME.toByte()) simBike.awake = true
            virtualCluster.apply(plain)
            linkFrames.incrementAndGet()
            emit("sim", tag, "plain" to hex(plain))
            return
        }
        val g = gatt ?: return
        val owner = ownerBike()
        if (owner == null || !g.device.address.equals(owner, ignoreCase = true)) {
            emit("link", "blocked_not_owner_bike", "addr" to g.device.address, "owner" to owner)
            return
        }
        // Until the bike's own frames reveal its key, use the bootstrap key: the bike only starts streaming
        // real data after the phone's handshake, so waiting for its frames first would deadlock.
        val key = linkKey ?: bootstrapKey
        val c = g.getService(apacheService)?.getCharacteristic(apacheWrite) ?: return
        val enc = if (encrypt) Protocol.xor(plain, key) else plain
        enqueue {
            val rc = g.writeCharacteristic(c, enc, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            linkFrames.incrementAndGet()
            emit("link", tag, "plain" to hex(plain), "sent" to hex(enc), "rc" to rc)
            if (rc != BluetoothStatusCodes.SUCCESS) opDone()
        }
    }

    private val cyclic = object : Runnable {
        override fun run() {
            if (!linkOn) return
            if (gattState == "connected") writeApache(mobileDataFrame(), "mobile_data")
            handler.postDelayed(this, 2000)
        }
    }

    private var linkGen = 0

    /** Same order as ApacheBleService: name x5 (400 ms apart), mobile data, then mobile data every 2 s.
     *  A new run cancels any earlier one (generation check), so sequences never overlap. */
    fun runStartupSequence() {
        val gen = ++linkGen
        handler.removeCallbacks(cyclic)
        if (linkKey == null) emit("link", "handshake_bootstrap", "key" to "%02X".format(bootstrapKey))
        val name = Protocol.riderNameFrame(linkName)
        repeat(5) { n -> handler.postDelayed({ if (linkOn && gen == linkGen) writeApache(name, "rider_name") }, n * 400L) }
        handler.postDelayed({ if (linkOn && gen == linkGen) handler.post(cyclic) }, 5 * 400L)
    }

    // ---- cluster lab: notification-area text (display only, 20-byte 5B frames, ASCII) -------------------------

    /** Text slots seen in the official app for this cluster family. */
    val textSlots = linkedMapOf(
        "Custom line" to 0x4C, "Rider name" to 0x52, "Caller" to 0x43, "SMS" to 0x53,
        "Music title" to 0x62, "Music artist" to 0x65, "Music album" to 0x61, "Location" to 0x5C
    )

    private fun sendFrameTimes(frame: ByteArray, tag: String, times: Int) {
        repeat(times) { n -> handler.postDelayed({ writeApache(frame, tag) }, n * 300L) }
    }

    fun sendTextSlot(id: Int, text: String, times: Int = 3): String {
        if (!linked()) return "Connect to the bike first"
        emit("lab", "text_slot", "id" to "%02X".format(id), "text" to text)
        sendFrameTimes(Protocol.textFrame(id, text), "text_%02X".format(id), times)
        return "Sent \"${text.take(17)}\" to slot %02X".format(id)
    }

    fun sendFeedback(feedbackId: Int, text: String, times: Int = 3): String {
        if (!linked()) return "Connect to the bike first"
        emit("lab", "feedback", "id" to feedbackId, "text" to text)
        sendFrameTimes(Protocol.feedbackFrame(feedbackId, text), "feedback_$feedbackId", times)
        return "Sent feedback id $feedbackId with \"${text.take(15)}\""
    }

    fun showClaude(): String = sendTextSlot(0x4C, "HELLO FROM CLAUDE")

    // ---- navigation (display only): start sequence once, then a nav + maneuver frame every 400 ms ---------------

    @Volatile var navStep: Protocol.NavStep? = null
    @Volatile var navText = ""
    private var navGen = 0

    private val navTick = object : Runnable {
        override fun run() {
            val s = navStep ?: return
            if (gattState == "connected") {
                writeApache(Protocol.navFrame(s), "nav")
                // the line above the arrow: only when there is something useful (road name), like the official app
                if (navText.isNotBlank()) writeApache(Protocol.maneuverFrame(navText), "maneuver")
            }
            handler.postDelayed(this, 400)
        }
    }

    /** Show a turn on the cluster. Call again with new values to update; [stopNavigation] to end. */
    fun showNavigation(step: Protocol.NavStep, maneuver: String): String {
        if (gattState != "connected") return "Connect to the bike first"
        val starting = navStep == null
        navStep = step
        navText = maneuver
        if (starting) {
            val gen = ++navGen
            Protocol.navStartFrames().forEachIndexed { i, f ->
                handler.postDelayed({ if (gen == navGen) writeApache(f, "nav_start", encrypt = false) }, i * 300L)
            }
            handler.postDelayed({ if (gen == navGen && navStep != null) handler.post(navTick) }, 700)
            emit("nav", "start", "picto" to step.pictogram)
        }
        return "Navigation on cluster: ${maneuver.ifBlank { "pictogram ${step.pictogram}" }}"
    }

    fun stopNavigation(): String {
        val last = navStep
        navStep = null
        navGen++
        handler.removeCallbacks(navTick)
        // nav status 0 = guidance ended (what the official app sends on arrival), so the cluster drops the arrow
        if (last != null && gattState == "connected") {
            val end = Protocol.navFrame(last.copy(navStatus = 0, distToTurnM = 0))
            repeat(2) { n -> handler.postDelayed({ if (navStep == null) writeApache(end, "nav_end") }, n * 300L) }
        }
        emit("nav", "stop")
        return "Navigation off"
    }

    /** Demo: right turn in 100 m (the case captured from the official app). */
    fun navDemo() = showNavigation(Protocol.NavStep(distToTurnM = 100, minutesLeft = 5, distLeftM = 1500, pictogram = 3), "TURN RIGHT")

    // ---- Google Maps → cluster ------------------------------------------------------------------------------------

    @Volatile var lastNavInfo: NavInfo? = null
    private var lastNavText = ""

    /** User-confirmed arrow codes from the arrow test, saved as prefs "picto_<MANEUVER>". */
    fun pictoOverrides(): Map<Maneuver, Int> = Maneuver.values().mapNotNull { m ->
        prefs(this).getInt("picto_" + m.name, -1).takeIf { it >= 0 }?.let { m to it }
    }.toMap() + calibration?.arrowCodes.orEmpty().mapNotNull { (k, v) -> Maneuver.entries.firstOrNull { it.name == k }?.let { it to v } }

    fun savePicto(m: Maneuver, code: Int) = prefs(this).edit().putInt("picto_" + m.name, code).apply()

    private var lastNavDist = 0
    private var lastNavManeuver: Maneuver? = null

    /**
     * One source of truth on the cluster for each turn: either the arrow (a confirmed pictogram) with distance and
     * the road name above it, or — for turns whose arrow isn't confirmed yet — one text line and NO arrow, so a stale
     * arrow can never point the other way from the text.
     */
    private var lastNavRaw: String? = null
    private var lastNavRawAt = 0L

    fun onNavInfo(info: NavInfo, raw: String? = null) {
        // Maps posts each update twice in a row; one is enough
        val nowMs = System.currentTimeMillis()
        if (raw != null && raw == lastNavRaw && nowMs - lastNavRawAt < 1000) return
        lastNavRaw = raw; lastNavRawAt = nowMs
        val newRoute = lastNavInfo == null && gattState == "connected"
        val sameTurn = info.maneuver == lastNavManeuver
        val distKnown = info.distToTurnM != null || (sameTurn && lastNavDist > 0)
        // Maps sometimes drops the distance for an update; keep the last one for the same turn instead of "0 m"
        val dist = info.distToTurnM ?: if (sameTurn) lastNavDist else 0
        lastNavDist = dist; lastNavManeuver = info.maneuver
        lastNavInfo = info.copy(distToTurnM = dist)
        if (newRoute) { routeIntro(info); BikeWidget.refresh(this) }
        if (gattState != "connected") return
        // "Head south" is just "ride on": the straight arrow. A compass arrow (↓ for south) would read as "turn back".
        // A step with only a place and a distance ("70 m · Lake View") means carry on towards it: straight too,
        // rather than dropping the arrow.
        val maneuver = if (info.maneuver == Maneuver.UNKNOWN) Maneuver.STRAIGHT else info.maneuver
        // an arrow the rider said their dash draws wrong (calibration) is left out: the turn goes as text
        val code = Pictograms.code(maneuver, pictoOverrides()).takeIf { calibration?.arrowOk(maneuver) != false }
        if (code != null) {
            // the help line (custom line, the only text the cluster shows next to the arrow; 5B4F is not drawn)
            var help = NavParser.helpLine(info, dist, scrollLong()).ifBlank { " " }
            // a long way to the next turn: every other 8 s, when you'll arrive instead of the road name
            val mins = info.minutesLeft
            if (help.length <= Marquee.WIDTH && dist >= 2000 && mins != null && mins > 0 && (nowMs / 8000) % 2 == 1L && prefs(this).getBoolean("etaOnCluster", true))
                help = ClusterLines.eta(mins, java.time.LocalTime.now())
            if (help != lastNavText) { lastNavText = help; if (nowMs >= introUntil) keepLine(help) }
            // prefix as the official app sets it: 3/4 = turn left/right now (< 65 m), 1 = in ..., 5 = straight on (≥ 1 km)
            val prefix = when {
                dist < 65 && "LEFT" in info.maneuver.name -> 3
                dist < 65 && "RIGHT" in info.maneuver.name -> 4
                dist < 1000 -> 1
                else -> 5
            }
            showNavigation(Protocol.NavStep(dist, info.minutesLeft ?: 0, info.distLeftM ?: 0, code, prefix = prefix),
                info.road?.let { Protocol.clusterText(it).take(16) } ?: "")
        } else {
            if (navStep != null) stopNavigation()
            val unit = if (dist >= 1000) "%.1fKM".format(Locale.US, dist / 1000.0).replace('.', ':') else "${dist}M"
            // no distance (e.g. "Head south" at the start of a route): Maps' own words, never a made-up "0M"
            val text = if (distKnown) (NavParser.shortText(info.maneuver).ifBlank { "GO" } + " " + unit).take(17)
                else Protocol.clusterText(info.instruction).take(17).ifBlank { NavParser.shortText(info.maneuver) }
            if (text != lastNavText) { lastNavText = text; if (nowMs >= introUntil) keepLine(text) }
        }
        emit("nav", "maps", "maneuver" to info.maneuver.name, "dist" to info.distToTurnM, "picto" to code,
            "road" to info.road, "raw" to raw?.takeIf { detailedLog })
    }

    // ---- start of a route: what Google says about it (km, time, arrival — with live traffic), how that compares
    // with your usual time, then the weather; each a few seconds on the custom line before the turns take over -------

    /** Until then the line belongs to the route intro; the route's help line comes after. */
    @Volatile private var introUntil = 0L
    private val introQueue = LinkedList<Pair<String, Long>>()
    private var introStartedAt = 0L
    @Volatile var lastWeather: Pair<String, WeatherNow>? = null; private set

    private fun routeIntro(info: NavInfo) {
        introQueue.clear()
        introStartedAt = System.currentTimeMillis()
        val mins = info.minutesLeft
        if (mins != null && mins > 0 && prefs(this).getBoolean("etaOnCluster", true)) {
            introQueue += ClusterLines.route(info.distLeftM, mins, java.time.LocalTime.now()) to 6000L
            Places.recentUsualMinutes(this)?.let { usual -> RouteInfo.trafficLine(mins, usual)?.let { introQueue += it to 5000L } }
        }
        weatherForRoute()
        if (introQueue.isNotEmpty()) nextIntro()
        else if (Weather.key(this) != null && prefs(this).getBoolean("weatherOnCluster", true)) introUntil = introStartedAt + 5000 // wait for the weather
        prefs(this).edit().remove("navDest").apply()
    }

    private val introTick = Runnable { nextIntro() }

    private fun nextIntro() {
        handler.removeCallbacks(introTick)
        val next = introQueue.pollFirst()
        if (next == null || lastNavInfo == null || callOnLine) { endIntro(); return }
        introUntil = System.currentTimeMillis() + next.second
        keepLine(next.first, force = true)
        handler.postDelayed(introTick, next.second)
    }

    private fun weatherForRoute() {
        val key = Weather.key(this) ?: return
        if (!prefs(this).getBoolean("weatherOnCluster", true)) return
        val dest = Places.recentDestination(this)
        val here = lastLocation ?: runCatching {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
        val (lat, lon) = dest ?: here?.let { it.latitude to it.longitude } ?: return
        val prefix = if (dest != null) "DEST" else "HERE"
        Thread {
            val w = runCatching { Weather.fetch(key, lat, lon) }.getOrNull()
            handler.post {
                emit("nav", "weather", "where" to prefix, "ok" to (w != null), "source" to w?.source)
                if (w == null || lastNavInfo == null || System.currentTimeMillis() - introStartedAt > 25_000) {
                    // nothing to add: if the intro was only waiting for the weather, hand the line to the route now
                    if (!handler.hasCallbacks(introTick)) endIntro()
                    return@post
                }
                lastWeather = prefix to w
                introQueue += w.clusterLine(prefix) to 6000L
                // the intro already finished (or was only waiting for this): show it now
                if (!handler.hasCallbacks(introTick)) nextIntro()
            }
        }.start()
    }

    private fun endIntro() {
        handler.removeCallbacks(introTick)
        introQueue.clear()
        introUntil = 0L
        if (lastNavInfo != null && lastNavText.isNotEmpty()) keepLine(lastNavText, force = true)
    }

    fun onNavEnded() {
        handler.removeCallbacks(introTick); introQueue.clear(); introUntil = 0L
        prefs(this).edit().remove("navDestName").apply()
        handler.post { BikeWidget.refresh(this) }
        val last = lastNavInfo
        lastNavInfo = null
        lastNavText = ""
        lastNavManeuver = null
        // the route ended at (or right by) the destination: say so on the dash for a few seconds, then clear
        val arrived = last != null && (last.maneuver == Maneuver.DESTINATION || (last.distLeftM ?: Int.MAX_VALUE) <= 150 ||
            ((last.distToTurnM ?: Int.MAX_VALUE) <= 50 && last.minutesLeft == 0))
        if (arrived && linked()) {
            emit("nav", "arrived")
            showNavigation(Protocol.NavStep(0, 0, 0, Pictograms.CONFIRMED.getValue(Maneuver.DESTINATION), prefix = 0), "")
            keepLine("YOU HAVE ARRIVED")
            handler.postDelayed({
                if (lastNavInfo == null) { if (navStep != null) stopNavigation(); keepLine(" "); resetCompass() }
            }, ARRIVED_SHOW_MS)
            return
        }
        if (navStep != null) stopNavigation()
        keepLine(" ") // clear the turn text
        handler.postDelayed({ resetCompass() }, 1000)
    }

    // ---- "Test directions": every display code in a row, to be filmed on the cluster (parked only) -------------

    @Volatile var codeTestIndex = -1; private set
    private var codeTestGen = 0
    val codeTestRunning get() = codeTestIndex >= 0

    private fun parkedNow() = (lastDecoded["5A10"]?.let { Protocol.u(bytesOf(it), 3) } ?: 0) == 0

    /** Where the last unfinished test stopped (-1 = none), so it can be resumed instead of replayed from the start. */
    fun codeTestResumeAt() = prefs(this).getInt("codeTestAt", -1)

    /** The steps of the running (or last) test: every code, or the short distance test. */
    @Volatile var codeTestSteps: List<CodeTest.Step> = CodeTest.steps(); private set
    private var codeTestResumable = true

    fun startCodeTest(from: Int = 0, distance: Boolean = false): String {
        if (!linked()) return "Connect to the bike first"
        if (!parkedNow()) return "Only while the bike is stopped"
        val gen = ++codeTestGen
        val steps = if (distance) CodeTest.distanceSteps() else CodeTest.steps()
        codeTestSteps = steps; codeTestResumable = !distance
        codeTestIndex = from.coerceIn(0, steps.lastIndex)
        emit("test", "start", "steps" to steps.size, "step_ms" to CodeTest.STEP_MS, "from" to codeTestIndex)
        if (navStep != null) stopNavigation()
        sendTextSlot(Protocol.ID_CUSTOM_LINE, if (codeTestIndex > 0) "TEST RESUME" else "TEST START", times = 1)
        var moving = 0
        fun run(i: Int) {
            if (gen != codeTestGen) return
            // one speed reading above 0 can be a glitch: stop only when it says moving twice in a row
            moving = if (parkedNow()) 0 else moving + 1
            val why = when { i >= steps.size -> null; !linked() -> "link lost"; moving >= 2 -> "bike moving"; else -> "" }
            if (why != "") { finishCodeTest(why == null, why); return }
            codeTestIndex = i
            if (codeTestResumable) prefs(this).edit().putInt("codeTestAt", i).apply()
            val s = steps[i]
            emit("test", "step", "i" to i, "kind" to s.kind.name, "value" to s.value, "label" to s.label)
            val nav = CodeTest.navStep(s)
            if (nav != null) showNavigation(nav, s.label)
            else {
                if (navStep != null) stopNavigation()
                sendFeedback(s.value, s.label, times = 1)
            }
            handler.postDelayed({ run(i + 1) }, CodeTest.STEP_MS)
        }
        handler.postDelayed({ run(codeTestIndex) }, 3000)
        val left = (steps.size - codeTestIndex) * CodeTest.STEP_MS / 1000
        return (if (codeTestIndex > 0) "Resumed at ${steps[codeTestIndex].label}" else "Test started: ${steps.size} codes") +
            ", about ${left / 60 + 1} minutes"
    }

    fun stopCodeTest() { if (codeTestRunning) finishCodeTest(false, "stop tapped") }

    /** Why the last test ended early, for the UI ("link lost", "bike moving", "stop tapped"); null = none. */
    @Volatile var codeTestStopReason: String? = null; private set

    private fun finishCodeTest(complete: Boolean, why: String? = null) {
        codeTestGen++
        emit("test", if (complete) "end" else "stopped", "at" to codeTestIndex, "why" to why)
        codeTestStopReason = if (complete) null else why
        if (codeTestResumable) prefs(this).edit().putInt("codeTestAt", if (complete) -1 else codeTestIndex).apply()
        codeTestIndex = -1
        if (navStep != null) stopNavigation()
        if (linked()) handler.postDelayed({ sendTextSlot(Protocol.ID_CUSTOM_LINE, if (complete) "TEST END" else "TEST STOPPED", times = 1) }, 700)
    }

    /** Arrow test (parked only): label on the custom line, then the arrow for that code, so the rider can map codes. */
    fun showArrowCode(code: Int): String {
        val speed = lastDecoded["5A10"]?.let { Protocol.u(bytesOf(it), 3) } ?: 0
        if (speed > 0) return "Arrow test only works while the bike is stopped"
        return showNavigation(Protocol.NavStep(100, 1, 100, code), "ARROW $code")
    }

    private fun bytesOf(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Envelope counter on the cluster (unread messages); 0 to clear. */
    @Volatile var unreadSms = 0

    fun startPhoneLink(name: String): String {
        if (!linked()) return "Connect to the bike first"
        if (linkOn) return "Phone link is already ON"
        linkName = name.ifBlank { "RIDER" }
        linkOn = true
        emit("link", "start", "name" to linkName, "key" to linkKey?.let { "%02X".format(it) })
        runStartupSequence()
        return "Phone link ON — name x5, then mobile data every 2 s"
    }

    fun stopPhoneLink(): String {
        linkOn = false
        linkGen++
        handler.removeCallbacks(cyclic)
        emit("link", "stop", "frames" to linkFrames.get())
        return "Phone link OFF"
    }

    // ---- button / switch watch: record every change of the switch & state bytes with timestamps ---------------

    /** Bytes (1-based) that the official parser labels as switch/state: 5A10 b12 = switch status / location tag;
     *  5A11 b3 diag2, b4 state1, b7 state2, b11 diag1, b13 turn indicators, b14 tell-tales, b16 state3, b17 ABS/MIL. */
    private val watched = mapOf(0x10 to listOf(12), 0x11 to listOf(3, 4, 7, 11, 13, 14, 16, 17))
    private val lastWatched = HashMap<String, Int>()
    private val buttonLog = LinkedList<String>()
    @Volatile var switchByte = -1
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun watchButtons(d: ByteArray) {
        if (d[0] != 0x5A.toByte()) return
        val id = d[1].toInt() and 0xFF
        if (id == 0x10 || id == 0x11) frameListener?.invoke(d)
        // a calibrated i switch on another byte than 5A10 b12
        calibration?.info?.takeIf { it.key != "5A10.b12" && it.frameId == id }?.let { sig ->
            val held = sig.matches(d)
            onInfoSwitch(held)
            if (!held && callFlow.awaitingRelease) callFlow.released(System.currentTimeMillis())?.let(::doCall)
        }
        val positions = watched[id] ?: return
        for (p in positions) {
            val v = d[p - 1].toInt() and 0xFF
            val key = "5A%02X.b%d".format(id, p)
            if (key == "5A10.b12") {
                switchByte = v
                // 0x80 = engine stopped. The i switch reads 4, but while a call rings the cluster reports it as 2
                // (seen on Rohit's RTR 160 4V, 11 Oct 2026: i pressed on two incoming calls → b12 = 2 for ~10 s)
                val s = v and 0x7F
                val info = calibration?.info
                if (info == null || info.key == "5A10.b12") {
                    val held = info?.matches(d) ?: (s == 4)
                    onInfoSwitch(held || (s == 2 && callFlow.isRinging))
                    // the declining press is over: now offer the text back
                    if (!held && s != 2 && callFlow.awaitingRelease) callFlow.released(System.currentTimeMillis())?.let(::doCall)
                }
            }
            val old = lastWatched[key]
            lastWatched[key] = v
            if (old == null || old == v) continue
            val line = "%s  %s  %02X → %02X  (%s → %s)".format(clock.format(Date()), key, old, v,
                Integer.toBinaryString(old).padStart(8, '0'), Integer.toBinaryString(v).padStart(8, '0'))
            synchronized(buttonLog) { buttonLog.add(line); while (buttonLog.size > 30) buttonLog.removeFirst() }
            emit("button", "change", "field" to key, "from" to old, "to" to v, "changed_bits" to (old xor v))
        }
    }

    fun buttonChanges(n: Int): List<String> = synchronized(buttonLog) { buttonLog.takeLast(n).reversed() }

    /** Latest decoded frame per incoming data id, e.g. "5A10" -> hex. */
    val lastDecoded = ConcurrentHashMap<String, String>()

    // ---- system broadcasts: bonding, pairing request, ACL, adapter state ----------------------------------------

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            val addr = dev?.address
            val name = try { dev?.name } catch (_: Exception) { null }
            val relevant = addr != null && (addr == selected || Bikes.looksLikeBike(name))
            when (i.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (relevant) {
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                    emit("sys", "bond", "addr" to addr, "state" to bondName(st), "prev" to bondName(prev),
                        "reason" to i.getIntExtra("android.bluetooth.device.extra.REASON", -1))
                    if (st == BluetoothDevice.BOND_BONDED && pendingBondConnect && addr == selected) {
                        pendingBondConnect = false
                        handler.postDelayed({ if (wantConnected) openGatt(dev!!, false) }, 600)
                    }
                    if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) pendingBondConnect = false
                }
                BluetoothDevice.ACTION_PAIRING_REQUEST -> if (relevant) {
                    emit("sys", "pairing_request", "addr" to addr,
                        "variant" to i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1),
                        "key" to i.getIntExtra("android.bluetooth.device.extra.PAIRING_KEY", -1))
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (relevant) emit("sys", "acl_connected", "addr" to addr)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (relevant) emit("sys", "acl_disconnected", "addr" to addr)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    emit("sys", "adapter_state", "state" to st)
                    if (st == BluetoothAdapter.STATE_OFF && !simulating) {
                        try { gatt?.close() } catch (_: Exception) {}
                        gatt = null
                        gattState = "idle"
                        rideStopped()
                    } else if (st == BluetoothAdapter.STATE_ON && autoMode && autoEnabled(ctx)) {
                        handler.postDelayed({ autoStart() }, 2000) // Bluetooth back on: re-arm the wait for the bike
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversOn) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, sysReceiver, f, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(this, smsSentReceiver, IntentFilter(ACTION_SMS_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, smsInReceiver, IntentFilter(android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            ContextCompat.RECEIVER_EXPORTED)
        receiversOn = true
    }

    private fun unregisterReceivers() {
        if (!receiversOn) return
        for (r in listOf(sysReceiver, callReceiver, smsSentReceiver, smsInReceiver)) try { unregisterReceiver(r) } catch (_: Exception) {}
        receiversOn = false
    }

    // ---- calls: the caller on the cluster, i declines, hold i to text "riding, will call back" ------------------

    val callsOn get() = prefs(this).getBoolean("callsToCluster", true)
    private val infoButton = InfoButton()
    val callFlow = CallFlow()
    /** What the cluster showed for the last call (name, or the number when the name can't be drawn). */
    @Volatile var lastCaller: String? = null; private set
    @Volatile private var smsStatus = 0
    @Volatile private var smsStatusUntil = 0L
    /** The custom line belongs to the call while it rings and while "hold i" is on offer. */
    private val callOnLine get() = callFlow.isRinging || callFlow.promptNumber != null

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            @Suppress("DEPRECATION") // still delivered with READ_CALL_LOG; the only way to get the number without a dialer role
            onCallState(i.getStringExtra(TelephonyManager.EXTRA_STATE), i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
        }
    }

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val ok = resultCode == android.app.Activity.RESULT_OK
            emit("call", "sms_result", "ok" to ok, "code" to resultCode)
            showSmsStatus(if (ok) 1 else 2)
        }
    }

    /** Phone call state (from the system broadcast, or the emulator's pretend call with a [name]). */
    fun onCallState(state: String?, number: String?, name: String? = null, real: Boolean = true) {
        val now = System.currentTimeMillis()
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Android sends RINGING twice when the number is readable (without it, then with it): keep the one with it
                if (callFlow.isRinging && (number == null || number == callFlow.ringingNumber)) return
                callFlow.ring(number)
                callReal = real
                // the first broadcast has no number; the second (with it) follows within ~100 ms. Wait for it, so the
                // cluster doesn't flash UNKNOWN first.
                if (number == null && real && granted(android.Manifest.permission.READ_CALL_LOG)) {
                    handler.postDelayed({ if (callFlow.isRinging && callFlow.ringingNumber == null) showCaller(null, null) }, 800)
                    return
                }
                showCaller(name, number)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (callOnLine) {
                emit("call", "answered")
                callFlow.answered(); afterCall()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> callFlow.ended(now)?.let { emit("call", "missed"); doCall(it) }
        }
    }

    /** Real call (system broadcast) or the emulator's pretend one: only a real one is really declined / texted back. */
    @Volatile private var callReal = false

    private fun showCaller(name: String?, number: String?) {
        val shown = CallerText.of(name ?: contactName(number), number)
        lastCaller = shown.text
        emit("call", "ringing", "shown" to shown.text, "used_number" to shown.usedNumber, "real" to callReal,
            "not_drawable" to shown.dropped.ifEmpty { null })
        if (!callsOn || !linked()) return
        sendTextSlot(Protocol.ID_CALLER, shown.text, times = 3)
        callLine("I = DECLINE CALL")
        handler.removeCallbacks(callTick); handler.postDelayed(callTick, 4000)
    }

    private fun onInfoSwitch(held: Boolean) {
        val now = System.currentTimeMillis()
        val e = infoButton.feed(held, now) ?: return
        val a = callFlow.button(e, infoButton.pressId, now)
        emit("button", "info", "event" to e.name, "action" to a?.javaClass?.simpleName)
        if (a != null) doCall(a)
        else if (e == InfoButton.Event.DOWN && smsWho != null) dismissSms()
        else if (e == InfoButton.Event.DOWN && unreadSms > 0) unreadSms = 0 // a tap clears the envelope
        else if (e == InfoButton.Event.LONG && smsWho == null && !callOnLine) openAssistant()
    }

    /**
     * Holding i with nothing on the line (no call, no offer, no text) opens the phone's assistant (Gemini). A service
     * may only open a screen while the app has "Display over other apps"; without it nothing happens (logged).
     */
    private fun openAssistant() {
        // off unless the rider turns it on: with the phone locked in a pocket the assistant can't do much (12 Oct ride)
        if (!prefs(this).getBoolean("assistantOnHold", false)) return
        val overlay = android.provider.Settings.canDrawOverlays(this)
        if (!overlay) {
            // Android drops the launch silently without it, so say so instead of pretending
            emit("button", "assistant", "opened" to false, "why" to "display over other apps is off")
            getSystemService(NotificationManager::class.java).notify(NOTIF_UPDATE + 1, Notification.Builder(this, CHANNEL)
                .setContentTitle("Gemini on the i switch needs one permission")
                .setContentText("Allow \"Display over other apps\" for RevTale (tap).")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 3,
                    Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")),
                    android.app.PendingIntent.FLAG_IMMUTABLE))
                .build())
            return
        }
        // Aimed at the phone's default assistant app (Settings → Default apps → Digital assistant; Google/Gemini here).
        // A bare VOICE_COMMAND goes to Bixby on Samsung (its headset receiver claims it), so never send it untargeted.
        val assistant = runCatching { android.provider.Settings.Secure.getString(contentResolver, "assistant") }.getOrNull()
            ?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: "com.google.android.googlequicksearchbox"
        val intents = listOf(Intent("android.intent.action.VOICE_ASSIST").setPackage(assistant),
            Intent(Intent.ACTION_ASSIST).setPackage(assistant), Intent(Intent.ACTION_VOICE_COMMAND).setPackage(assistant),
            packageManager.getLaunchIntentForPackage("com.google.android.apps.bard"))
        val ok = intents.filterNotNull().any { i ->
            runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        }
        emit("button", "assistant", "opened" to ok, "app" to assistant)
    }

    // ---- texts back: after "I'm riding…", their replies scroll on the cluster until i is pressed ----------------

    /** Numbers we texted back, with when: only their replies are shown (for 3 h). */
    private val textedBack = ConcurrentHashMap<String, Long>()
    /** The name the cluster showed for that call ("MOM"), so the reply reads the same. */
    private val textedName = ConcurrentHashMap<String, String>()
    @Volatile private var smsWho: String? = null
    private var smsNumber: String? = null
    private val smsMessages = mutableListOf<String>()
    private var smsPages: List<String> = emptyList()
    private var smsPage = 0

    private val smsInReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val parts = runCatching { android.provider.Telephony.Sms.Intents.getMessagesFromIntent(i) }.getOrNull() ?: return
            parts.groupBy { it.originatingAddress }.forEach { (from, p) -> onSmsReceived(from, p.joinToString("") { it.messageBody ?: "" }) }
        }
    }

    /** A text arrived (from the system, or the emulator's pretend reply). */
    /** Kept in prefs too, so a reply still shows if the service restarted after the text-back. */
    private fun rememberTextedBack(number: String) {
        textedBack[number] = System.currentTimeMillis(); lastCaller?.let { textedName[number] = it }
        prefs(this).edit().putString("textedBack", textedBack.entries.joinToString(";") { (n, t) -> "$n,$t,${textedName[n] ?: ""}" }).apply()
    }

    private fun loadTextedBack() {
        if (textedBack.isNotEmpty()) return
        prefs(this).getString("textedBack", null)?.split(';')?.forEach { e ->
            val p = e.split(','); if (p.size >= 2) { textedBack[p[0]] = p[1].toLongOrNull() ?: 0; if (p.getOrNull(2)?.isNotBlank() == true) textedName[p[0]] = p[2] }
        }
    }

    private val recentBodies = LinkedList<Pair<String, Long>>()

    /**
     * A message notification from Samsung Messages / Google Messages (covers RCS chats, which never arrive as an SMS
     * broadcast). [sender] is the contact name or the number as the app shows it.
     */
    fun onMessageNotification(sender: String, body: String) {
        loadTextedBack()
        val now = System.currentTimeMillis()
        val shown = CallerText.of(sender, null).text
        val number = textedBack.entries.firstOrNull { (n, t) ->
            now - t < 3 * 3600_000L && (SmsPager.sameNumber(n, sender) || textedName[n] == shown)
        }?.key ?: return
        onSmsReceived(number, body)
    }

    fun onSmsReceived(from: String?, body: String) {
        loadTextedBack()
        val now = System.currentTimeMillis()
        // every new text lights the dash's envelope (with a count) until the rider clears it with i
        synchronized(seenSms) {
            seenSms.removeAll { now - it.second > 120_000 }
            if (seenSms.none { it.first == body.trim() }) { seenSms.add(body.trim() to now); unreadSms = (unreadSms + 1).coerceAtMost(99) }
        }
        val ours = textedBack.entries.any { (n, t) -> now - t < 3 * 3600_000L && SmsPager.sameNumber(n, from) }
        emit("sms", "in", "from_texted_back" to ours)
        if (!ours || !callsOn || body.isBlank()) return
        // the same text can come twice (SMS broadcast and the messaging app's notification)
        synchronized(recentBodies) {
            recentBodies.removeAll { now - it.second > 120_000 }
            if (recentBodies.any { it.first == body.trim() }) return
            recentBodies.add(body.trim() to now)
        }
        if (smsNumber == null || !SmsPager.sameNumber(smsNumber, from)) { smsMessages.clear(); smsNumber = from }
        smsMessages += body
        val who = textedName.entries.firstOrNull { SmsPager.sameNumber(it.key, from) }?.value ?: CallerText.of(contactName(from), from).text
        smsWho = who
        smsPages = if (scrollLong()) Marquee.frames(SmsPager.text(who, smsMessages)) else SmsPager.pages(SmsPager.text(who, smsMessages))
        smsPage = 0
        handler.removeCallbacks(smsTick); handler.post(smsTick)
    }

    /** One page every 2 s, round and round, until i. Never while a call owns the line. */
    private val smsTick = object : Runnable {
        override fun run() {
            if (smsWho == null || smsPages.isEmpty()) return
            if (!callOnLine && linked()) {
                sendFrameTimes(Protocol.textFrame(Protocol.ID_CUSTOM_LINE, smsPages[smsPage % smsPages.size]), "text_4C", 1)
                smsPage++
            }
            // scrolling: a step every 300 ms, holding the start; pages: one every 2 s
            val scrolling = scrollLong() && smsPages.size > 1
            handler.postDelayed(this, when { !scrolling -> SMS_PAGE_MS; smsPage % smsPages.size == 1 -> Marquee.HOLD_MS; else -> Marquee.STEP_MS })
        }
    }

    private val seenSms = mutableListOf<Pair<String, Long>>()

    private fun dismissSms() {
        emit("sms", "dismissed")
        unreadSms = 0
        smsWho = null; smsNumber = null; smsMessages.clear(); smsPages = emptyList()
        handler.removeCallbacks(smsTick)
        keepLine(keptLine, force = true)
        if (lastNavInfo == null) resetCompass()
    }

    private fun doCall(a: CallFlow.Action) {
        when (a) {
            is CallFlow.Action.Decline -> { declineCall(); doCall(callFlow.declined(System.currentTimeMillis())) }
            is CallFlow.Action.OfferSms -> {
                if (callsOn && linked()) callLine(if (callFlow.awaitingRelease) "CALL DECLINED" else "HOLD I: TEXT BACK")
                handler.removeCallbacks(callTick); handler.postDelayed(callTick, 1000)
            }
            is CallFlow.Action.SendSms -> { sendRidingSms(a.number); afterCall() }
            is CallFlow.Action.Clear -> afterCall()
        }
    }

    /** While ringing: the caller again every 4 s (the cluster's pop-up times out). While offering: end it on time. */
    private val callTick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (callFlow.isRinging) {
                lastCaller?.let { if (callsOn && linked()) sendTextSlot(Protocol.ID_CALLER, it, times = 1) }
                handler.postDelayed(this, 4000)
            } else if (callFlow.promptNumber != null) {
                callFlow.tick(now)?.let(::doCall) ?: handler.postDelayed(this, 1000)
            }
        }
    }

    /** Give the custom line back: the route's help line, else the compass, else blank. */
    private fun afterCall() {
        handler.removeCallbacks(callTick)
        if (!linked()) return
        callText = ""
        if (lastNavInfo != null && lastNavText.isNotEmpty()) keepLine(lastNavText, force = true)
        else { keepLine(" ", force = true); resetCompass() }
    }

    private fun granted(p: String) = checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun contactName(number: String?): String? {
        if (number.isNullOrBlank() || !granted(android.Manifest.permission.READ_CONTACTS)) return null
        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        return runCatching {
            contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    private fun declineCall() {
        if (!callReal) { emit("call", "declined", "emulator" to true); return }
        val ok = granted(android.Manifest.permission.ANSWER_PHONE_CALLS) && runCatching {
            @Suppress("DEPRECATION") getSystemService(android.telecom.TelecomManager::class.java).endCall()
        }.getOrDefault(false)
        emit("call", "declined", "ok" to ok)
    }

    val ridingSms get() = prefs(this).getString("callBackSms", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SMS

    private fun sendRidingSms(number: String) {
        if (!callReal) { emit("call", "sms", "emulator" to true, "text" to ridingSms); rememberTextedBack(number); showSmsStatus(1); return }
        if (!granted(android.Manifest.permission.SEND_SMS)) { emit("call", "sms", "ok" to false, "why" to "no permission"); showSmsStatus(2); return }
        runCatching {
            val sent = android.app.PendingIntent.getBroadcast(this, 0, Intent(ACTION_SMS_SENT).setPackage(packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val sms = getSystemService(android.telephony.SmsManager::class.java)
            val parts = sms.divideMessage(ridingSms)
            sms.sendMultipartTextMessage(number, null, parts, ArrayList(parts.map { sent }), null)
            rememberTextedBack(number)
            emit("call", "sms", "sending" to true)
        }.onFailure { emit("call", "sms", "ok" to false, "why" to it.message); showSmsStatus(2) }
    }

    /** The cluster's own "SMS SENT SUCCESS" (1) / "SMS NOT SENT" (2), carried by the next few status frames. */
    private fun showSmsStatus(status: Int) {
        smsStatus = status; smsStatusUntil = System.currentTimeMillis() + 6000
        if (linkOn && linked()) writeApache(mobileDataFrame(), "mobile_data")
    }

    // ---- fuel: notice a fill-up from the fuel bars, then ask the rider what it cost --------------------------------

    private var fuelWatcher: FuelWatcher? = null
    private var lastFuelMs = 0L
    /** A fill-up seen in the emulator; kept in memory only so tests never reach the real fuel log. */
    @Volatile var simRefuel: PendingRefuel? = null

    /** The fill-up waiting for the rider's answer, if any. */
    fun pendingRefuel(): PendingRefuel? = simRefuel ?: PendingRefuel.fromPref(prefs(this).getString("pendingRefuel", null))

    fun clearRefuel() {
        if (simRefuel != null) simRefuel = null else prefs(this).edit().remove("pendingRefuel").apply()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_FUEL)
    }

    private fun watchFuel(p: ByteArray, now: Long) {
        val key = if (simulating) "fuelBars_sim" else "fuelBars"
        // the emulator notices a "fill-up" after 5 s, parked or not, so it can be tried at a desk
        val w = fuelWatcher ?: (if (simulating) FuelWatcher(simBike.fuelBars, 5.0) else FuelWatcher(prefs(this).getInt(key, -1)))
            .also { fuelWatcher = it }
        val dt = if (lastFuelMs == 0L) 0.0 else ((now - lastFuelMs) / 1000.0).coerceIn(0.0, 5.0)
        lastFuelMs = now
        val before = w.known
        val odo = Protocol.u(p, 4, 3) / 10.0
        val seen = w.feed(Protocol.u(p, 7), if (simulating || Protocol.u(p, 3) > 0) dt else 0.0)
        if (!simulating && w.known != before) prefs(this).edit().putInt(key, w.known).putFloat("lastOdo", odo.toFloat()).apply()
        if (seen == null) return
        val refuel = PendingRefuel(now, odo, seen.first, seen.second, simulating)
        if (simulating) simRefuel = refuel else prefs(this).edit().putString("pendingRefuel", refuel.pref()).apply()
        emit("fuel", "refuel_seen", "from" to seen.first, "to" to seen.second, "odo" to odo)
        getSystemService(NotificationManager::class.java).notify(NOTIF_FUEL, Notification.Builder(this, CHANNEL)
            .setContentTitle("Saw you fuel up!")
            .setContentText("Fuel ${seen.first} → ${seen.second} bars. Tap to log what you filled and get your mileage.")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setAutoCancel(true)
            .setContentIntent(Open.pending(this, Open.RIDES))
            .build())
    }

    // ---- app updates in the background: every 6 h while the service runs, a notification when one is out --------

    private val updateTick = object : Runnable {
        override fun run() {
            checkUpdateInBackground()
            handler.postDelayed(this, 6 * 3600_000L)
        }
    }

    private fun checkUpdateInBackground() {
        Thread {
            val result = Updater.checkAndNotify(this)
            emit("update", "check", "found" to result.getOrNull()?.version, "error" to result.exceptionOrNull()?.message)
        }.start()
    }

    // ---- simulator: a pretend bike, so the app and everything it sends to the cluster can be tried at a desk ----

    @Volatile var simulating = false
    var simBike = SimBike(); private set
    val virtualCluster = VirtualCluster()

    /** Connected for display purposes: a real GATT link or the simulator. */
    private fun linked() = gattState == "connected" && (gatt != null || simulating)

    private val simTick = object : Runnable {
        override fun run() {
            if (!simulating) return
            simBike.step(0.25)
            // frames take the same path as real notifications: key detection, decoding, live ride, Now Bar
            for (f in simBike.frames()) onBikeFrame(Protocol.xor(f, SimBike.KEY), "SIMULATOR", "simulator")
            if (simBike.speed > 7) updateCompass(simBike.heading.toFloat())
            handler.postDelayed(this, 250)
        }
    }

    /** Pretend a bike connected. Nothing goes over Bluetooth; frames for the bike land in [virtualCluster]. */
    fun startSimulation(): String {
        if (simulating) return "Simulator is already running"
        if (gattState == "connected") return "Disconnect from your bike first"
        if (!sessionOn) startSession(scan = false)
        // only waiting for the bike (auto-connect armed): pause that wait, and re-arm it when the emulator stops
        resumeAutoAfterSim = autoMode || gattState == "connecting"
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        pendingBondConnect = false
        stopScan()
        wantConnected = false; autoMode = false
        simBike = SimBike(); virtualCluster.reset(); lastDecoded.clear(); liveRide = null
        keyDetector = KeyDetector(); linkKey = null; bootstrapKey = Protocol.HANDSHAKE_KEY; compassSector = -1
        fuelWatcher = null; lastFuelMs = 0L; simRefuel = null
        simulating = true
        selected = "SIMULATOR"
        gattState = "connected"
        emit("sim", "start")
        handler.post(simTick)
        // same as after a real connection: the phone link starts by itself unless turned off
        if (prefs(this).getBoolean("autoLink", true)) handler.postDelayed({
            if (simulating && !linkOn) startPhoneLink(prefs(this).getString("name", null) ?: "RIDER")
        }, 1500)
        return "Simulated bike connected"
    }

    fun stopSimulation() {
        if (!simulating) return
        stopCodeTest()
        if (navStep != null) stopNavigation()
        if (linkOn) stopPhoneLink()
        handler.removeCallbacks(simTick)
        simulating = false
        gattState = "idle"
        selected = prefs(this).getString("bike", null)
        lastDecoded.clear(); linkKey = null; liveRide = null; heading = null; lastNavInfo = null; keptLine = " "
        fuelWatcher = null; lastFuelMs = 0L; simRefuel = null
        emit("sim", "stop")
        postNotif()
        if (resumeAutoAfterSim) { resumeAutoAfterSim = false; handler.postDelayed({ if (!simulating) autoStart() }, 500) }
    }

    private var resumeAutoAfterSim = false

    // ---- the custom line: the cluster hides it after ~3 s, so whatever should stay up is sent again every 3 s ------

    /** What the line should show (help line, compass); [callText] wins while a call owns the line. */
    @Volatile private var keptLine = " "
    @Volatile private var callText = ""

    private fun keepLine(text: String, force: Boolean = false) {
        if (text == keptLine && !force) return
        keptLine = text
        // longer than the line: scroll it (rider's choice, Cluster tab), else cut to fit
        val frames = if (scrollLong()) Marquee.frames(text) else listOf(text.take(Marquee.WIDTH))
        lineFrames = frames; lineFrame = 0
        if (!callOnLine && smsWho == null && linked()) sendTextSlot(Protocol.ID_CUSTOM_LINE, frames[0], times = 1)
        handler.removeCallbacks(lineTick)
        handler.postDelayed(lineTick, if (frames.size > 1) Marquee.HOLD_MS else LINE_REFRESH_MS)
    }

    /** The custom line's frames: one, or the scrolling windows of a long text. */
    private var lineFrames = listOf(" ")
    private var lineFrame = 0
    fun scrollLong() = prefs(this).getBoolean("scrollText", true)

    private fun callLine(text: String) {
        callText = text
        if (linked()) sendTextSlot(Protocol.ID_CUSTOM_LINE, text, times = 1)
        handler.removeCallbacks(lineTick); handler.postDelayed(lineTick, LINE_REFRESH_MS)
    }

    private val lineTick = object : Runnable {
        override fun run() {
            if (smsWho != null && !callOnLine) { handler.postDelayed(this, LINE_REFRESH_MS); return } // the text owns the line
            if (callOnLine && callText.isNotBlank()) {
                if (linked() && linkOn) sendFrameTimes(Protocol.textFrame(Protocol.ID_CUSTOM_LINE, callText), "text_4C", 1)
                handler.postDelayed(this, LINE_REFRESH_MS); return
            }
            if (keptLine.isBlank() || !linked() || !linkOn) return
            // quietly (no log line per frame): the same frame again, or the next step of a scrolling text
            val scrolling = lineFrames.size > 1
            if (scrolling) lineFrame = (lineFrame + 1) % lineFrames.size
            sendFrameTimes(Protocol.textFrame(Protocol.ID_CUSTOM_LINE, lineFrames[lineFrame]), "text_4C", 1)
            handler.postDelayed(this, when { !scrolling -> LINE_REFRESH_MS; lineFrame == 0 -> Marquee.HOLD_MS; else -> Marquee.STEP_MS })
        }
    }

    // ---- compass: heading from GPS on the cluster's custom line when no navigation is running ------------------

    /** Direction of travel in degrees (0 = north), null until the first fix while moving. */
    @Volatile var heading: Float? = null
    private var compassSector = -1

    private fun updateCompass(bearing: Float) {
        heading = bearing
        val sector = Compass.stable(compassSector, bearing)
        if (sector == compassSector) return
        if (lastNavInfo != null || callOnLine || !linkOn || gattState != "connected" || idleKinds() != setOf("compass")) return
        compassSector = sector
        // "^ SOUTH": the caret reads as "riding towards" (the cluster font has no arrows in text)
        keepLine(ClusterLines.heading(sector))
    }

    /** Show the heading again (after navigation ends or on a new connection). */
    private fun resetCompass() { compassSector = -1; heading?.let { if (lastNavInfo == null) updateCompass(it) } }

    // ---- phone GPS ----------------------------------------------------------------------------------------------

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastLocation = l
            // GPS course is reliable only when moving (> ~7 km/h); the last heading is kept while stopped
            if (l.hasBearing() && l.hasSpeed() && l.speed > 2f) updateCompass(l.bearing)
            if ("area" in idleKinds()) lookUpArea(l)
            if (detailedLog) emit("phone", "gps",
                "lat" to l.latitude, "lon" to l.longitude, "alt" to l.altitude,
                "speed_kmh" to if (l.hasSpeed()) l.speed * 3.6f else null,
                "bearing" to if (l.hasBearing()) l.bearing else null,
                "acc_m" to if (l.hasAccuracy()) l.accuracy else null,
                "fix_ms" to l.time)
        }
    }

    /** Road and area names for the dash, from Android's geocoder (needs internet): every 150 m, at most every 20 s. */
    private fun lookUpArea(l: Location) {
        val now = System.currentTimeMillis()
        if (now - areaAskedMs < 20_000 || (areaAt?.distanceTo(l) ?: Float.MAX_VALUE) < 150f) return
        if (!android.location.Geocoder.isPresent()) return
        areaAskedMs = now
        runCatching {
            android.location.Geocoder(this, Locale.ENGLISH).getFromLocation(l.latitude, l.longitude, 1) { list ->
                val a = list.firstOrNull() ?: return@getFromLocation
                ClusterLines.area(a.thoroughfare, a.subLocality ?: a.locality)?.let { areaText = it; areaAt = l }
            }
        }
    }

    private fun startGps() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            gpsOn = true
            emit("phone", "gps_start")
        } catch (e: Exception) {
            emit("phone", "gps_error", "msg" to e.message)
        }
    }

    private fun stopGps() {
        if (!gpsOn) return
        try { (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(locListener) } catch (_: Exception) {}
        gpsOn = false
    }

    // ---- phone motion sensors (10 Hz) ---------------------------------------------------------------------------

    private val lastSensorLog = HashMap<Int, Long>()
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            if (!optMotion) return
            val nowMs = e.timestamp / 1_000_000L
            val last = lastSensorLog[e.sensor.type] ?: 0L
            if (nowMs - last < 100) return
            lastSensorLog[e.sensor.type] = nowMs
            val name = if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) "accel" else "gyro"
            emit("phone", name, "x" to e.values[0], "y" to e.values[1], "z" to e.values[2])
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun startSensors() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE).forEach { t ->
            sm.getDefaultSensor(t)?.let { sm.registerListener(sensorListener, it, 100_000) }
        }
        sensorsOn = true
    }

    private fun stopSensors() {
        if (!sensorsOn) return
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(sensorListener)
        sensorsOn = false
    }
}
