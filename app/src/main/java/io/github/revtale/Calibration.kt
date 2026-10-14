package io.github.revtale

import android.content.Context
import org.json.JSONObject

/**
 * Bike calibration: like setting up a universal remote, the rider presses each switch and confirms what the dash
 * shows, so other connected models (and other years of the RTR) work without code changes. Only display frames are
 * sent while calibrating; switches are only read.
 *
 * Defaults (no calibration) are what Rohit's Apache RTR 160 4V reports: i switch = 5A10 b12 value 4.
 */

/** One switch as the bike reports it: the bits of [key] ("5A10.b12") in [mask] read [bits] while it is held. */
data class SwitchSignal(val key: String, val mask: Int, val bits: Int) {
    val frameId: Int get() = key.substring(2, 4).toInt(16)
    val pos: Int get() = key.substringAfter(".b").toInt()

    fun matches(frame: ByteArray): Boolean {
        if (frame.size < pos || (frame[1].toInt() and 0xFF) != frameId) return false
        return (frame[pos - 1].toInt() and 0xFF and mask) == bits
    }

    fun json(): JSONObject = JSONObject().put("key", key).put("mask", mask).put("bits", bits)

    companion object {
        fun of(o: JSONObject?) = o?.let { SwitchSignal(it.getString("key"), it.getInt("mask"), it.getInt("bits")) }
    }
}

/** What the rider confirmed. Null fields were skipped and keep the defaults. */
data class BikeCalibration(
    val atMs: Long,
    val bikeName: String,
    val switches: Map<String, SwitchSignal> = emptyMap(),
    val fuelMax: Int? = null,
    val nameShown: Boolean? = null,
    val lineShown: Boolean? = null,
    val arrows: Map<String, Boolean> = emptyMap(),
    /** Raw 5A11 b6 low nibble → gear the dash showed (0 = N); empty = not calibrated. */
    val gears: Map<Int, Int> = emptyMap(),
    /** Maneuver name → the arrow code the rider picked when the default one looked wrong on their dash. */
    val arrowCodes: Map<String, Int> = emptyMap(),
) {
    /** False when the rider said no arrow on their dash fits [m]: the turn goes to the dash as text instead. */
    fun arrowOk(m: Maneuver) = arrows[m.name] != false || m.name in arrowCodes

    val info get() = switches[INFO]

    fun json(): String = JSONObject()
        .put("v", 1).put("at", atMs).put("bike", bikeName)
        .put("switches", JSONObject().also { o -> switches.forEach { (k, s) -> o.put(k, s.json()) } })
        .put("fuelMax", fuelMax ?: JSONObject.NULL)
        .put("nameShown", nameShown ?: JSONObject.NULL).put("lineShown", lineShown ?: JSONObject.NULL)
        .put("arrows", JSONObject(arrows as Map<*, *>))
        .put("gears", JSONObject().also { o -> gears.forEach { (raw, g) -> o.put(raw.toString(), g) } })
        .put("arrowCodes", JSONObject(arrowCodes as Map<*, *>))
        .toString()

    companion object {
        const val INFO = "info"; const val MODE = "mode"
        const val LEFT = "left"; const val RIGHT = "right"; const val HIGH_BEAM = "highBeam"

        fun parse(s: String?): BikeCalibration? = runCatching {
            val o = JSONObject(s ?: return null)
            val sw = o.optJSONObject("switches")
            BikeCalibration(
                o.getLong("at"), o.optString("bike"),
                sw?.keys()?.asSequence()?.associateWith { SwitchSignal.of(sw.getJSONObject(it))!! } ?: emptyMap(),
                o.optInt("fuelMax", -1).takeIf { it > 0 },
                if (o.isNull("nameShown")) null else o.optBoolean("nameShown"),
                if (o.isNull("lineShown")) null else o.optBoolean("lineShown"),
                o.optJSONObject("arrows")?.let { a -> a.keys().asSequence().associateWith { a.getBoolean(it) } } ?: emptyMap(),
                o.optJSONObject("gears")?.let { g -> g.keys().asSequence().associate { it.toInt() to g.getInt(it) } } ?: emptyMap(),
                o.optJSONObject("arrowCodes")?.let { a -> a.keys().asSequence().associateWith { a.getInt(it) } } ?: emptyMap())
        }.getOrNull()
    }
}

object Calibration {
    private const val PREF = "calibration"
    /** Full-tank bars when the rider hasn't told us (the RTR 160 4V's six-segment reading before 13 Oct 2026). */
    const val DEFAULT_FUEL_MAX = 6

    fun load(ctx: Context): BikeCalibration? = BikeCalibration.parse(BleService.prefs(ctx).getString(PREF, null))
        .also { Protocol.gearMap = it?.gears ?: emptyMap() }
    fun save(ctx: Context, c: BikeCalibration) {
        BleService.prefs(ctx).edit().putString(PREF, c.json()).apply()
        Protocol.gearMap = c.gears
    }
    fun clear(ctx: Context) { BleService.prefs(ctx).edit().remove(PREF).apply(); Protocol.gearMap = emptyMap() }

    /** Bars on a full tank: what the rider said, else the default, never below what the bike just reported. */
    fun fuelMax(ctx: Context, now: Int? = null): Int =
        maxOf(load(ctx)?.fuelMax ?: DEFAULT_FUEL_MAX, now ?: 0)
}

/**
 * Finds the switch the rider is pressing: compares 5A10 / 5A11 frames with a baseline taken while nothing was held,
 * ignoring bytes that move on their own (speed, odometer, fuel, throttle, RPM, gear, mode, checksums and the
 * notification bits in 5A11 b11 / b14). A change that holds for [holdMs] and then goes back is a press.
 */
class SwitchFinder(private val holdMs: Long = 300) {
    private val baseline = HashMap<String, Int>()
    private var candidate: SwitchSignal? = null
    private var since = 0L
    var found: SwitchSignal? = null; private set
    /** The change being seen right now, for the screen ("5A10 b12 → 4"). */
    var seeing: Pair<String, Int>? = null; private set

    fun reset() { baseline.clear(); candidate = null; found = null; seeing = null }

    /** Feed every 5A10 / 5A11 frame (plain, decoded); returns the switch once it was pressed and let go. */
    fun feed(frame: ByteArray, now: Long): SwitchSignal? {
        if (found != null || frame.size < 20 || frame[0] != 0x5A.toByte()) return found
        val id = frame[1].toInt() and 0xFF
        val positions = WATCH[id] ?: return null
        var change: SwitchSignal? = null
        for (p in positions) {
            val key = "5A%02X.b%d".format(id, p)
            val v = frame[p - 1].toInt() and 0xFF
            val base = baseline.getOrPut(key) { v }
            if (v != base && change == null) change = SwitchSignal(key, v xor base, v and (v xor base))
        }
        val c = candidate
        if (change != null) {
            seeing = change.key to (frame[change.pos - 1].toInt() and 0xFF)
            if (c == null || c.key != change.key) { candidate = change; since = now }
        } else if (c != null && c.frameId == id) {
            // back to rest: a press if it was held long enough, else a blip
            if (now - since >= holdMs) found = c
            candidate = null; seeing = null
        }
        return found
    }

    companion object {
        /** 1-based byte positions that only change when the rider does something. */
        val WATCH = mapOf(0x10 to listOf(8, 9, 12, 13, 14, 15, 16), 0x11 to listOf(3, 4, 5, 7, 8, 9, 10, 12, 13, 15, 16, 17, 19))
    }
}
