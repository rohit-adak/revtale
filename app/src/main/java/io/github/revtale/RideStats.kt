package io.github.revtale

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One ride sample (about 1 per second, when the bike sends 5A10). lat/lon are NaN without a GPS fix. */
data class Sample(
    val tMs: Long, val speedKmh: Int, val rpm: Int, val gear: Int, val throttle: Int,
    val lat: Double = Double.NaN, val lon: Double = Double.NaN, val volts: Double = Double.NaN,
    /** Riding mode reported by the bike ([RideModes]); -1 when unknown (older ride files). */
    val mode: Int = -1,
    /** Engine temperature °C (5A18 b9) and intake air °C (b8); -1 when unknown (rides before 0.14). */
    val engineC: Int = -1, val airC: Int = -1
) {
    fun csv() = "$tMs,$speedKmh,$rpm,$gear,$throttle,${if (lat.isNaN()) "" else "%.6f".format(java.util.Locale.US, lat)}," +
        "${if (lon.isNaN()) "" else "%.6f".format(java.util.Locale.US, lon)},${if (volts.isNaN()) "" else "%.1f".format(java.util.Locale.US, volts)}," +
        (if (mode < 0) "" else "$mode") + "," + (if (engineC < 0) "" else "$engineC") + "," + (if (airC < 0) "" else "$airC")

    companion object {
        const val CSV_HEADER = "t_ms,speed_kmh,rpm,gear,throttle,lat,lon,volts,mode,engine_c,air_c"
        fun parse(line: String): Sample? = try {
            val p = line.split(',')
            // gear through Protocol.gear: rides recorded before the fix stored the raw byte (e.g. 0x23 = gear 3)
            Sample(p[0].toLong(), p[1].toInt(), p[2].toInt(), Protocol.gear(p[3].toInt(), mapped = false), p[4].toInt(),
                p.getOrNull(5)?.toDoubleOrNull() ?: Double.NaN, p.getOrNull(6)?.toDoubleOrNull() ?: Double.NaN,
                p.getOrNull(7)?.toDoubleOrNull() ?: Double.NaN, p.getOrNull(8)?.toIntOrNull() ?: -1,
                p.getOrNull(9)?.toIntOrNull() ?: -1, p.getOrNull(10)?.toIntOrNull() ?: -1)
        } catch (_: Exception) { null }
    }
}

/** Per-gear performance: how long you were in it, how hard you used it, and its speed/RPM ratio. */
data class GearStats(
    val gear: Int, val seconds: Double, val sharePct: Double, val topSpeed: Int, val maxRpm: Int, val avgRpm: Int,
    /** km/h per 1000 RPM — a stable fingerprint of the gear ratio; shows clutch slip or wrong gear readings. */
    val kmhPer1000Rpm: Double?
)

/** A stretch of road (≈100 m grid cell) with the speeds you carried through it. */
data class Spot(val lat: Double, val lon: Double, val topSpeed: Int, val avgSpeed: Int, val passes: Int)

data class RideSummary(
    val durationS: Double, val movingS: Double, val distanceKm: Double, val topSpeed: Int, val avgMovingSpeed: Double,
    val maxRpm: Int, val gears: List<GearStats>, val best0to60S: Double?, val speedHistogram: Map<Int, Double>,
    val rpmHistogram: Map<Int, Double>, val shiftUpRpm: Map<Int, Int>, val spots: List<Spot>,
    /** Engine running → stopped while the ignition stayed on (stalls, kill switch, traffic stops). */
    val engineStops: Int,
    /** Engine running but the bike standing still. */
    val idleS: Double,
    /** Speed drops of ≥ [RideStats.HARD_EVENT_KMH_PER_S] km/h in one second / rises of the same. */
    val hardBrakes: Int, val hardLaunches: Int,
    val maxDecelKmhPerS: Int, val maxAccelKmhPerS: Int,
    /** Seconds spent at or above [RideStats.HIGH_RPM]. */
    val highRpmS: Double,
    /** Seconds ridden in each riding mode ([RideModes]); empty for rides recorded before modes were logged. */
    val modeS: Map<Int, Double> = emptyMap()
)

/** How one riding mode was used: moving time, average speed, average throttle (bike's raw units) and RPM. */
data class ModeUse(val seconds: Double, val avgSpeed: Double, val avgThrottle: Double, val avgRpm: Int)

/** Findings derived from the samples ([RideStats.insights]); null = not enough data for that one. */
data class RideInsights(
    /** + = the speedometer reads higher than GPS, in %; from [speedoSamples] steady windows. */
    val speedoErrorPct: Double?, val speedoSamples: Int,
    /** Halts of 2 s or more after moving (traffic, signals). */
    val stops: Int, val stoppedS: Double, val longestStopS: Double,
    val upshifts: Int, val downshifts: Int,
    /** Share of moving time (≥ 5 km/h) rolling with the clutch pulled or in neutral / in gear with the throttle shut. */
    val clutchPct: Double?, val engineBrakePct: Double?,
    val avgThrottle: Double?, val throttleClosedPct: Double?,
    /** Share of moving seconds where speed changed by ≤ 3 km/h. */
    val smoothPct: Double?,
    val best0to40S: Double?, val fastestKmS: Double?,
    val voltsMin: Double?, val voltsMax: Double?, val voltsIdle: Double?, val voltsRevving: Double?,
    val modes: Map<Int, ModeUse>
)

/** Riding mode from 5A11 byte 18 (low nibble). Urban → Sport seen on a real RTR 160 4V; Rain not yet confirmed. */
object RideModes {
    fun of(byte18: Int): Int = byte18 and 0x0F
    fun name(mode: Int): String = when (mode) { 1 -> "Urban"; 2 -> "Sport"; 3 -> "Rain"; else -> "Mode $mode" }
}

/** Ride analytics from samples. Pure Kotlin, unit-tested; the UI only displays the result. */
object RideStats {

    /** "At this pace": minutes to cover [remainingKm] at [avgKmh] (null when not moving). */
    fun etaMinutes(remainingKm: Double, avgKmh: Double): Double? = if (avgKmh > 1) remainingKm / avgKmh * 60 else null

    /** "At this pace": how far you get in [minutes] — e.g. rings on the map for 15 / 30 / 60 min. */
    fun reachKm(avgKmh: Double, minutes: Double): Double = avgKmh * minutes / 60

    private fun haversineKm(a: Sample, b: Sample): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    fun summarize(samples: List<Sample>): RideSummary {
        val s = samples.sortedBy { it.tMs }
        // seconds each sample "owns": gap to the next sample, capped so a dropout doesn't count as riding
        val dt = s.indices.map { i -> if (i + 1 < s.size) ((s[i + 1].tMs - s[i].tMs) / 1000.0).coerceIn(0.0, 5.0) else 0.0 }
        val duration = if (s.size > 1) (s.last().tMs - s.first().tMs) / 1000.0 else 0.0
        var moving = 0.0
        var distSpeed = 0.0
        var distGps = 0.0
        for (i in s.indices) {
            if (s[i].speedKmh > 0) moving += dt[i]
            distSpeed += s[i].speedKmh * dt[i] / 3600.0
            if (i + 1 < s.size && !s[i].lat.isNaN() && !s[i + 1].lat.isNaN() && dt[i] > 0) distGps += haversineKm(s[i], s[i + 1])
        }
        // the bike's own speed is the reference; GPS is used only when the bike speed is missing
        val distance = if (distSpeed > 0) distSpeed else distGps

        val inGear = s.indices.filter { s[it].gear > 0 }
        val gearTime = inGear.sumOf { dt[it] }
        val gears = inGear.groupBy { s[it].gear }.toSortedMap().map { (g, idx) ->
            val secs = idx.sumOf { dt[it] }
            val ratios = idx.filter { s[it].rpm > 1500 && s[it].speedKmh > 5 }.map { s[it].speedKmh * 1000.0 / s[it].rpm }.sorted()
            GearStats(g, secs, if (gearTime > 0) secs * 100 / gearTime else 0.0,
                idx.maxOf { s[it].speedKmh }, idx.maxOf { s[it].rpm }, idx.map { s[it].rpm }.average().roundToInt(),
                ratios.getOrNull(ratios.size / 2)?.let { (it * 10).roundToInt() / 10.0 })
        }

        // RPM at which each gear was left for the next one up (median) — your shift points
        val shifts = HashMap<Int, MutableList<Int>>()
        for (i in 0 until s.size - 1) if (s[i].gear > 0 && s[i + 1].gear == s[i].gear + 1) shifts.getOrPut(s[i].gear) { mutableListOf() }.add(s[i].rpm)
        val shiftUp = shifts.mapValues { (_, v) -> v.sorted()[v.size / 2] }.toSortedMap()

        // best 0-60: from the last sample at 0 km/h to the first sample at ≥ 60
        var best: Double? = null
        var startT: Long? = null
        for (x in s) {
            if (x.speedKmh == 0) startT = x.tMs
            else if (x.speedKmh >= 60 && startT != null) {
                val t = (x.tMs - startT) / 1000.0
                if (t in 1.0..30.0 && (best == null || t < best)) best = t
                startT = null
            }
        }

        fun histogram(key: (Int) -> Int?): Map<Int, Double> {
            val m = sortedMapOf<Int, Double>()
            for (i in s.indices) key(i)?.let { m[it] = (m[it] ?: 0.0) + dt[i] }
            return m
        }
        val speedHist = histogram { i -> if (s[i].speedKmh > 0) s[i].speedKmh / 10 * 10 else null }
        val rpmHist = histogram { i -> if (s[i].rpm > 0) s[i].rpm / 1000 * 1000 else null }

        // location-wise speed on a ~100 m grid
        val cells = s.filter { !it.lat.isNaN() && it.speedKmh > 0 }.groupBy { (it.lat * 1000).roundToInt() to (it.lon * 1000).roundToInt() }
        val spots = cells.map { (_, v) ->
            Spot(v.map { it.lat }.average(), v.map { it.lon }.average(), v.maxOf { it.speedKmh }, v.map { it.speedKmh }.average().roundToInt(), v.size)
        }.sortedByDescending { it.topSpeed }

        var stops = 0
        var brakes = 0; var launches = 0; var maxDecel = 0; var maxAccel = 0
        for (i in 0 until s.size - 1) {
            if (s[i].rpm > 0 && s[i + 1].rpm == 0) stops++
            val gap = (s[i + 1].tMs - s[i].tMs) / 1000.0
            if (gap in 0.5..1.6) {
                val rate = ((s[i + 1].speedKmh - s[i].speedKmh) / gap).roundToInt()
                if (rate <= -HARD_EVENT_KMH_PER_S) brakes++
                if (rate >= HARD_EVENT_KMH_PER_S) launches++
                maxDecel = maxOf(maxDecel, -rate); maxAccel = maxOf(maxAccel, rate)
            }
        }
        val idle = s.indices.filter { s[it].rpm > 0 && s[it].speedKmh == 0 }.sumOf { dt[it] }
        val high = s.indices.filter { s[it].rpm >= HIGH_RPM }.sumOf { dt[it] }

        return RideSummary(duration, moving, (distance * 100).roundToInt() / 100.0, s.maxOfOrNull { it.speedKmh } ?: 0,
            if (moving > 0) distance / (moving / 3600.0) else 0.0, s.maxOfOrNull { it.rpm } ?: 0, gears, best,
            speedHist, rpmHist, shiftUp, spots, stops, idle, brakes, launches, maxDecel, maxAccel, high,
            histogram { i -> s[i].mode.takeIf { it >= 0 && s[i].speedKmh > 0 } })
    }

    /**
     * Things the samples already say but no single number shows: speedometer error against GPS, clutch and
     * coasting habits, traffic stops, the fastest kilometre, charging voltage and how each riding mode was used.
     */
    fun insights(samples: List<Sample>, gears: List<GearStats>): RideInsights {
        val s = samples.sortedBy { it.tMs }
        val dt = s.indices.map { i -> if (i + 1 < s.size) ((s[i + 1].tMs - s[i].tMs) / 1000.0).coerceIn(0.0, 5.0) else 0.0 }
        val movingIdx = s.indices.filter { s[it].speedKmh > 0 }
        val movingS = movingIdx.sumOf { dt[it] }

        // speedometer vs GPS over 5-sample windows at a steady speed; median, so GPS jumps don't matter
        val errors = ArrayList<Double>()
        for (i in 0 until s.size - 5) {
            val a = s[i]; val b = s[i + 5]
            if (a.lat.isNaN() || b.lat.isNaN()) continue
            val bike = (i until i + 5).map { s[it].speedKmh }.average()
            val secs = (b.tMs - a.tMs) / 1000.0
            if (bike < 25 || secs !in 2.0..10.0) continue
            val gps = haversineKm(a, b) / secs * 3600
            if (gps > 10) errors += (bike / gps - 1) * 100
        }
        val speedoError = if (errors.size >= 20) errors.sorted()[errors.size / 2] else null

        // traffic: every time the bike came to a halt, and for how long
        var stops = 0; var stopped = 0.0; var longest = 0.0; var cur = -1.0
        for (i in s.indices) {
            if (s[i].speedKmh == 0) { if (cur >= 0) cur += dt[i] else if (i > 0 && s[i - 1].speedKmh > 0) cur = dt[i] }
            else if (cur >= 0) { if (cur >= 2) { stops++; stopped += cur; longest = maxOf(longest, cur) }; cur = -1.0 }
        }

        var up = 0; var down = 0
        for (i in 0 until s.size - 1) {
            val a = s[i].gear; val b = s[i + 1].gear
            if (a > 0 && b > a) up++ else if (b in 1 until a) down++
        }

        // clutch: rolling in neutral, or the engine turning far slower than the gear ratio needs (lever pulled)
        val ratio = gears.associate { it.gear to it.kmhPer1000Rpm }
        var clutch = 0.0; var engineBrake = 0.0
        for (i in movingIdx) {
            val x = s[i]
            if (x.speedKmh < 5) continue
            val expected = ratio[x.gear]
            val pulled = x.gear == 0 || (expected != null && x.rpm > 0 && x.speedKmh * 1000.0 / x.rpm > expected * 1.35)
            if (pulled) clutch += dt[i] else if (x.throttle == 0) engineBrake += dt[i]
        }

        val throttles = movingIdx.map { s[it].throttle }
        // smooth seconds: speed changed by no more than 3 km/h
        var smooth = 0; var steps = 0
        for (i in 0 until s.size - 1) if (s[i].speedKmh > 0 && (s[i + 1].tMs - s[i].tMs) in 500..1600) {
            steps++; if (kotlin.math.abs(s[i + 1].speedKmh - s[i].speedKmh) <= 3) smooth++
        }

        // best 0-40 and the fastest kilometre (from the bike's own speed)
        var best40: Double? = null; var startT: Long? = null
        for (x in s) {
            if (x.speedKmh == 0) startT = x.tMs
            else if (x.speedKmh >= 40 && startT != null) {
                val t = (x.tMs - startT) / 1000.0
                if (t in 1.0..20.0 && (best40 == null || t < best40)) best40 = t
                startT = null
            }
        }
        val cum = DoubleArray(s.size + 1).also { for (i in s.indices) it[i + 1] = it[i] + s[i].speedKmh * dt[i] / 3600 }
        var fastest: Double? = null; var j = 0
        for (i in s.indices) {
            while (j < s.size && cum[j + 1] - cum[i] < 1.0) j++
            if (j >= s.size) break
            val secs = (s[j].tMs + (dt[j] * 1000).toLong() - s[i].tMs) / 1000.0
            if (secs > 0 && (fastest == null || secs < fastest)) fastest = secs
        }

        val volts = s.filter { !it.volts.isNaN() && it.volts > 5 }
        fun median(v: List<Double>) = v.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val modes = movingIdx.filter { s[it].mode >= 0 }.groupBy { s[it].mode }
            .filter { (_, idx) -> idx.sumOf { dt[it] } >= 30 }
            .mapValues { (_, idx) ->
                ModeUse(idx.sumOf { dt[it] }, idx.map { s[it].speedKmh }.average(), idx.map { s[it].throttle }.average(),
                    idx.map { s[it].rpm }.average().roundToInt())
            }.toSortedMap()

        return RideInsights(
            speedoError, errors.size, stops, stopped, longest, up, down,
            if (movingS > 0) clutch * 100 / movingS else null, if (movingS > 0) engineBrake * 100 / movingS else null,
            throttles.takeIf { it.isNotEmpty() }?.average(), throttles.takeIf { it.isNotEmpty() }?.let { t -> t.count { it == 0 } * 100.0 / t.size },
            if (steps >= 30) smooth * 100.0 / steps else null, best40, fastest,
            volts.minOfOrNull { it.volts }, volts.maxOfOrNull { it.volts },
            median(volts.filter { it.rpm in 1..2000 && it.speedKmh == 0 }.map { it.volts }),
            median(volts.filter { it.rpm > 4000 }.map { it.volts }), modes)
    }

    /** km/h change within one second that counts as a hard brake / hard launch. */
    const val HARD_EVENT_KMH_PER_S = 10
    /** RTR 160 4V redline is around 10 000 RPM; time near it is "high RPM". */
    const val HIGH_RPM = 9000
}
