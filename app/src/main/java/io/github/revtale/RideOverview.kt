package io.github.revtale

import kotlin.math.cos
import kotlin.math.roundToInt

/** How you use one gear across all rides: share of moving time, the speeds you hold in it, RPM, top speed. */
data class GearHabit(
    val gear: Int, val seconds: Double, val sharePct: Double, val avgSpeed: Int,
    /** Middle 80 % of the speeds you ride in this gear (10th–90th percentile). */
    val speedLow: Int, val speedHigh: Int, val avgRpm: Int, val topSpeed: Int, val shiftUpRpm: Int?
)

/** A place you start or end rides (~250 m cell). [label] is filled in by the app (address) or stays coordinates. */
data class Place(val lat: Double, val lon: Double, val visits: Int)

/** A trip you make again and again: from → to, how often, and your usual time and speed on it. */
data class UsualRoute(val from: Place, val to: Place, val count: Int, val avgKm: Double, val avgMinutes: Double, val avgSpeed: Double)

/** Where the bike was last switched off (end of the newest ride with a GPS fix). */
data class Parked(val lat: Double, val lon: Double, val atMs: Long)

data class RideOverview(
    val rides: Int, val km: Double, val movingS: Double, val topSpeed: Int, val avgMovingSpeed: Double,
    val gears: List<GearHabit>, val speedHistogram: Map<Int, Double>, val rpmHistogram: Map<Int, Double>,
    val routes: List<UsualRoute>, val places: List<Place>, val parked: Parked?
)

/** All rides together. Pure Kotlin, unit-tested; the app passes each ride's samples (oldest first or any order). */
object RideOverviews {
    private const val CELL_M = 250.0

    fun build(rides: List<List<Sample>>): RideOverview {
        val sorted = rides.map { r -> r.sortedBy { it.tMs } }.filter { it.size > 1 }.sortedBy { it.first().tMs }
        val summaries = sorted.map { RideStats.summarize(it) }

        // ---- gears: from every sample, so speed bands come from the whole history ----
        val bySpeed = HashMap<Int, MutableList<Pair<Int, Double>>>() // gear → (speed, seconds)
        val rpmSum = HashMap<Int, Double>()
        val top = HashMap<Int, Int>()
        for (s in sorted) for (i in 0 until s.size - 1) {
            val a = s[i]
            if (a.gear <= 0 || a.speedKmh <= 0) continue
            val dt = ((s[i + 1].tMs - a.tMs) / 1000.0).coerceIn(0.0, 5.0)
            bySpeed.getOrPut(a.gear) { mutableListOf() }.add(a.speedKmh to dt)
            rpmSum[a.gear] = (rpmSum[a.gear] ?: 0.0) + a.rpm * dt
            top[a.gear] = maxOf(top[a.gear] ?: 0, a.speedKmh)
        }
        val gearTime = bySpeed.values.sumOf { l -> l.sumOf { it.second } }
        // shift-up RPM: median of each ride's median, weighted by nothing (one vote per ride)
        val shifts = HashMap<Int, MutableList<Int>>()
        summaries.forEach { sm -> sm.shiftUpRpm.forEach { (g, r) -> shifts.getOrPut(g) { mutableListOf() }.add(r) } }
        val gears = bySpeed.toSortedMap().map { (g, l) ->
            val secs = l.sumOf { it.second }
            val ordered = l.sortedBy { it.first }
            fun pct(p: Double): Int {
                var acc = 0.0
                for ((v, w) in ordered) { acc += w; if (acc >= secs * p) return v }
                return ordered.last().first
            }
            GearHabit(g, secs, if (gearTime > 0) secs * 100 / gearTime else 0.0,
                (l.sumOf { it.first * it.second } / secs).roundToInt(), pct(0.1), pct(0.9),
                ((rpmSum[g] ?: 0.0) / secs).roundToInt(), top[g] ?: 0, shifts[g]?.sorted()?.let { it[it.size / 2] })
        }

        fun merge(hs: List<Map<Int, Double>>) = hs.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.toSortedMap()

        // ---- places and usual routes: start and end of each ride, snapped to ~250 m cells ----
        val ends = sorted.mapNotNull { s ->
            val a = s.firstOrNull { !it.lat.isNaN() }; val b = s.lastOrNull { !it.lat.isNaN() }
            if (a == null || b == null) null else Triple(a, b, s)
        }
        // a point belongs to the first place within CELL_M of it (grid cells would split two visits 50 m apart)
        val clusters = mutableListOf<MutableList<Sample>>()
        fun near(a: Sample, b: Sample): Boolean {
            val dy = (a.lat - b.lat) * 111_320.0
            val dx = (a.lon - b.lon) * 111_320.0 * cos(Math.toRadians(a.lat))
            return dx * dx + dy * dy <= CELL_M * CELL_M
        }
        fun cell(p: Sample): Int {
            val i = clusters.indexOfFirst { c -> near(c.first(), p) }
            if (i >= 0) { clusters[i].add(p); return i }
            clusters.add(mutableListOf(p)); return clusters.lastIndex
        }
        val tripCells = ends.map { (a, b, s) -> Triple(cell(a), cell(b), s) }
        fun place(c: Int): Place { val v = clusters[c]; return Place(v.map { it.lat }.average(), v.map { it.lon }.average(), v.size) }
        val places = clusters.indices.map(::place).sortedByDescending { it.visits }.take(5)

        val routes = tripCells.filter { it.first != it.second } // a loop from home back home isn't a route between places
            .groupBy { it.first to it.second }.map { (k, list) ->
                val sm = list.map { summaries[sorted.indexOf(it.third)] }
                UsualRoute(place(k.first), place(k.second), list.size, sm.map { it.distanceKm }.average(),
                    sm.map { it.durationS / 60 }.average(), sm.map { it.avgMovingSpeed }.average())
            }.sortedByDescending { it.count }.take(5)

        val parked = ends.lastOrNull()?.second?.let { Parked(it.lat, it.lon, it.tMs) }
        val moving = summaries.sumOf { it.movingS }
        val km = summaries.sumOf { it.distanceKm }
        return RideOverview(sorted.size, km, moving, summaries.maxOfOrNull { it.topSpeed } ?: 0,
            if (moving > 0) km / (moving / 3600) else 0.0, gears,
            merge(summaries.map { it.speedHistogram }), merge(summaries.map { it.rpmHistogram }), routes, places, parked)
    }
}
