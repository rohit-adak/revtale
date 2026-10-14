package io.github.revtale

import kotlin.math.roundToInt

/**
 * A pretend bike for testing without riding: produces the same 5A10 / 5A11 / 5A18 / 5A19 frames a real
 * RTR 160 4V streams (XOR key EB, checksum), using the field positions in PROTOCOL.md. Like the real bike it
 * stays silent until the phone's handshake (rider name) arrives. Pure Kotlin, unit-tested.
 */
class SimBike {
    companion object {
        const val KEY = 0xEB
        /** km/h per 1,000 RPM per gear, measured on a real ride (5th extrapolated). */
        val RATIO = doubleArrayOf(0.0, 3.8, 6.1, 8.6, 10.9, 12.8)
        const val IDLE_RPM = 1450

        /** City loop for auto ride: target speed (km/h) held for seconds. */
        val ROUTE = listOf(0 to 5, 45 to 14, 30 to 8, 60 to 16, 0 to 8, 35 to 12, 50 to 10, 0 to 6)
    }

    /** Starts parked; switch on for the city loop. */
    @Volatile var auto = false
    /** Manual mode: the speed the rider is aiming for. */
    @Volatile var targetKmh = 0
    @Volatile var mode = 1
    @Volatile var engineOn = true
    @Volatile var awake = false
    /** Fuel gauge bars; raise it to try the fill-up prompt. */
    @Volatile var fuelBars = 3
    /** The i (Info) switch on the handlebar, held down (5A10 b12 = 4). */
    @Volatile var infoHeld = false

    var speed = 0.0; private set
    var gear = 0; private set
    var rpm = IDLE_RPM; private set
    var throttle = 0; private set
    var heading = 0.0; private set
    var odoKm = 12_345.6; private set
    var tripKm = 0.0; private set
    var top = 0; private set
    private var temp = 32.0
    val tempC get() = temp.roundToInt()
    var volts = 12.6; private set
    private var leg = 0
    private var legT = 0.0
    private var headingGoal = 0.0

    /** Emulator: make the engine run very hot (to try the heat warning). */
    fun overheat() { temp = 160.0 }

    /** Advance the pretend ride by [dt] seconds. */
    fun step(dt: Double) {
        val goal = if (!engineOn) 0 else if (auto) autoTarget(dt) else targetKmh
        val accel = if (mode == 2) 9.0 else 6.0
        val before = speed
        speed = when {
            speed < goal -> minOf(goal.toDouble(), speed + accel * dt)
            speed > goal -> maxOf(goal.toDouble(), speed - 9.0 * dt)
            else -> speed
        }
        gear = when {
            !engineOn || speed < 1 -> if (speed < 1 && goal == 0) 0 else 1
            speed < 15 -> 1; speed < 28 -> 2; speed < 42 -> 3; speed < 55 -> 4; else -> 5
        }
        throttle = when {
            !engineOn -> 0
            speed > before + 0.01 -> if (mode == 2) 70 else 45
            speed < before - 0.01 -> 0
            speed > 0 -> 15
            else -> 0
        }
        rpm = when {
            !engineOn -> 0
            gear == 0 || speed < 1 -> IDLE_RPM + throttle * 20
            else -> maxOf(1800, (speed * 1000 / RATIO[gear]).roundToInt())
        }
        odoKm += speed * dt / 3600; tripKm += speed * dt / 3600
        top = maxOf(top, speed.roundToInt())
        if (engineOn && temp < 88.0) temp = minOf(88.0, temp + dt * 0.5)
        volts = if (!engineOn) 12.4 else if (rpm > 3000) 14.2 else 13.6
        // the heading eases towards the goal (a turn takes a few seconds), like a GPS course would
        val diff = ((headingGoal - heading + 540) % 360) - 180
        heading = (heading + diff.coerceIn(-30 * dt, 30 * dt) + 360) % 360
    }

    private fun autoTarget(dt: Double): Int {
        legT += dt
        if (legT >= ROUTE[leg].second) {
            legT = 0.0; leg = (leg + 1) % ROUTE.size
            if (ROUTE[leg].first > 0) headingGoal = (headingGoal + if (leg % 2 == 0) 90 else 270) % 360 // turn at each junction
        }
        return ROUTE[leg].first
    }

    /** The frames the bike would stream right now (plain, checksummed); empty until the phone's handshake. */
    fun frames(): List<ByteArray> {
        if (!awake) return emptyList()
        val s = speed.roundToInt()
        val a = frame(0x10).also {
            it[2] = s.toByte()
            val odo = (odoKm * 10).roundToInt(); it[3] = (odo shr 16).toByte(); it[4] = (odo shr 8).toByte(); it[5] = odo.toByte()
            it[6] = fuelBars.toByte(); it[9] = top.toByte(); it[10] = throttle.toByte()
            it[11] = (if (infoHeld) 4 else 0).toByte()
            it[16] = (rpm shr 8).toByte(); it[17] = rpm.toByte()
        }
        val b = frame(0x11).also { it[5] = gear.toByte(); it[17] = (0x20 or mode).toByte() }
        val c = frame(0x18).also { it[8] = tempC.toByte(); it[11] = (volts * 10).roundToInt().toByte() }
        val d = frame(0x19).also {
            val km = tripKm.toInt(); it[6] = (km shr 16).toByte(); it[7] = (km shr 8).toByte(); it[8] = km.toByte()
            it[9] = ((tripKm - km) * 10).toInt().toByte()
        }
        return listOf(a, b, c, d).map { Protocol.withChecksum(it) }
    }

    private fun frame(id: Int) = ByteArray(Protocol.FRAME_LEN).also { it[0] = Protocol.START_DATA.toByte(); it[1] = id.toByte(); it[19] = 0xFF.toByte() }
}

/** What the cluster is showing, as far as the app's frames tell it. */
data class ClusterView(
    val riderName: String = "", val line: String = "", val feedbackId: Int? = null,
    /** Latest text in another slot (caller, SMS, music, location) and when it arrived. */
    val popupLabel: String? = null, val popupText: String = "", val popupAt: Long = 0,
    val clock: String = "--:--", val date: String = "", val phoneBattery: Int = -1, val signal: Int = -1,
    val unread: Int = 0, val smsStatus: Int = 0,
    val navOn: Boolean = false, val navDistM: Int = 0, val navMinutesLeft: Int = 0, val navDistLeftM: Int = 0,
    val navPicto: Int = -1, val navPrefix: Int = 0, val navText: String = "",
    val frames: Long = 0, val lastFrameAt: Long = 0
)

/** Reads every frame the phone sends to the bike and keeps the resulting cluster state. Pure Kotlin, unit-tested. */
class VirtualCluster {
    @Volatile var view = ClusterView(); private set

    private fun text(p: ByteArray, from: Int, to: Int): String =
        (from until to).map { p[it].toInt() and 0xFF }.takeWhile { it != 0 }.map { it.toChar() }.joinToString("").trimEnd()

    private val slotNames = mapOf(0x43 to "Caller", 0x53 to "SMS", 0x62 to "Music", 0x65 to "Artist", 0x61 to "Album", 0x5C to "Location")

    fun apply(p: ByteArray, now: Long = System.currentTimeMillis()) {
        if (p.size != Protocol.FRAME_LEN) return
        val start = p[0].toInt() and 0xFF
        val id = p[1].toInt() and 0xFF
        var v = view.copy(frames = view.frames + 1, lastFrameAt = now)
        if (start == Protocol.START_INFO) when (id) {
            Protocol.ID_RIDER_NAME -> v = v.copy(riderName = text(p, 2, 19))
            Protocol.ID_CUSTOM_LINE -> v = when {
                text(p, 2, 19) == "Navigation" -> v.copy(navOn = true)
                p[2].toInt() == 0 && p[3].toInt() != 0 -> v.copy(line = text(p, 4, 19), feedbackId = p[3].toInt() and 0xFF)
                else -> v.copy(line = text(p, 2, 19), feedbackId = null)
            }
            0x4F -> v = v.copy(navText = text(p, 2, 18))
            Protocol.ID_MOBILE_DATA -> {
                val u = { i: Int -> p[i].toInt() and 0xFF }
                v = v.copy(signal = u(2) shr 4, phoneBattery = u(2) and 0x0F, smsStatus = u(5), unread = u(15),
                    clock = "%d:%02d %s".format(u(6), u(7), if (u(9) == 1) "PM" else "AM"),
                    date = "%02d/%02d/%02d".format(u(12), u(13), u(14)))
            }
            0x63 -> {}
            else -> slotNames[id]?.let { v = v.copy(popupLabel = it, popupText = text(p, 2, 19), popupAt = now) }
        } else if (start == Protocol.START_DATA && id == 0x4E) {
            val u = { i: Int -> p[i].toInt() and 0xFF }
            v = if (u(11) == 0) v.copy(navOn = false, navText = "", navPicto = -1)
            else v.copy(navOn = true, navDistM = u(2) shl 8 or u(3), navMinutesLeft = u(4) shl 8 or u(5),
                navDistLeftM = (u(6) shl 16) or (u(7) shl 8) or u(8), navPicto = u(9), navPrefix = u(14))
        }
        view = v
    }

    fun reset() { view = ClusterView() }
}
