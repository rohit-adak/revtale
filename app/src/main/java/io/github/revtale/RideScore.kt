package io.github.revtale

import java.util.Calendar
import kotlin.math.roundToInt

/** How smoothly a ride went, 0–100, with what cost points (biggest first) and a tip for each. */
data class RideScore(val score: Int, val label: String, val minus: List<Pair<String, Int>>, val tips: List<String>)

object RideScores {
    /** Null for rides under 1 km (too short to judge). Pure, unit-tested. */
    fun of(s: RideSummary, i: RideInsights?): RideScore? {
        if (s.distanceKm < 1.0 || s.movingS < 60) return null
        val per10 = 10.0 / s.distanceKm
        val parts = mutableListOf<Triple<String, Int, String>>()
        fun add(what: String, pts: Double, cap: Int, tip: String) {
            val p = pts.roundToInt().coerceIn(0, cap)
            if (p > 0) parts += Triple(what, p, tip)
        }
        add("Hard brakes", s.hardBrakes * per10 * 4, 25, "Look further ahead and roll off the throttle earlier instead of braking late.")
        add("Hard launches", s.hardLaunches * per10 * 3, 20, "Open the throttle progressively from a stop; it saves fuel and the clutch.")
        add("Near the redline", s.highRpmS * 100 / s.movingS * 2, 15, "Shift up before 9,000 RPM; the engine pulls well lower down.")
        i?.smoothPct?.let { add("Jerky speed", (70 - it) / 2, 15, "Hold a steady speed: fewer surges, better mileage.") }
        i?.clutchPct?.let { add("Riding the clutch", (it - 25) / 2, 10, "Let the clutch out fully once moving; coasting with it pulled wears it.") }
        val sorted = parts.sortedByDescending { it.second }
        val score = (100 - sorted.sumOf { it.second }).coerceIn(0, 100)
        val label = when { score >= 90 -> "Silky smooth"; score >= 75 -> "Smooth rider"; score >= 55 -> "A bit sharp"; else -> "Aggressive" }
        return RideScore(score, label, sorted.map { it.first to it.second }, sorted.take(2).map { it.third })
    }
}

/** One ride for the monthly report. */
data class RideMonthEntry(val startMs: Long, val km: Double, val movingS: Double, val topSpeed: Int, val score: Int?)

data class MonthReport(
    val year: Int, val month: Int, val rides: Int, val km: Double, val movingS: Double, val longestKm: Double,
    val topSpeed: Int, val avgScore: Int?, val fuelSpend: Double, val liters: Double,
    /** Previous month's km, for "+40 % vs September"; null when nothing was ridden then. */
    val prevKm: Double?, val busiestDay: String?
)

object MonthlyReports {
    private fun key(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.let { it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH) }

    /** The report for the month containing [nowMs]. Pure, unit-tested. */
    fun build(rides: List<RideMonthEntry>, fills: List<FillUp>, nowMs: Long = System.currentTimeMillis()): MonthReport {
        val k = key(nowMs)
        val mine = rides.filter { key(it.startMs) == k }
        val prev = rides.filter { key(it.startMs) == k - 1 }
        val f = fills.filter { key(it.tMs) == k }
        val days = mine.groupBy { Calendar.getInstance().apply { timeInMillis = it.startMs }.get(Calendar.DAY_OF_WEEK) }
            .maxByOrNull { (_, l) -> l.sumOf { it.km } }?.key
        val dayName = days?.let { listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")[it - 1] }
        val scores = mine.mapNotNull { it.score }
        return MonthReport(k / 12, k % 12, mine.size, mine.sumOf { it.km }, mine.sumOf { it.movingS },
            mine.maxOfOrNull { it.km } ?: 0.0, mine.maxOfOrNull { it.topSpeed } ?: 0,
            scores.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), f.sumOf { it.amount }, f.sumOf { it.liters },
            prev.sumOf { it.km }.takeIf { prev.isNotEmpty() }, dayName)
    }
}

/** Short texts for the cluster's custom line (17 drawable characters; no '.', '%' or '-'). */
object ClusterLines {
    /** End of ride: "12KM 34MIN AVG 41" (km with one decimal below 10, written with ':' as the cluster has no '.'). */
    fun trip(km: Double, movingS: Double, avgKmh: Double): String {
        val k = if (km < 10) "%.1f".format(java.util.Locale.US, km) else "${km.roundToInt()}"
        val mins = (movingS / 60).roundToInt()
        val t = if (mins >= 60) "${mins / 60}H${"%02d".format(mins % 60)}" else "${mins}MIN"
        return Protocol.clusterText("${k}KM $t AVG ${avgKmh.roundToInt()}").take(17)
    }

    /** Start of a route, from Google's own (live-traffic) estimate: "12KM 28MIN 4:08PM", or "ETA 4:08PM 28MIN" without km. */
    fun route(distLeftM: Int?, minutesLeft: Int, now: java.time.LocalTime): String {
        if (distLeftM == null || distLeftM <= 0) return eta(minutesLeft, now)
        val at = now.plusMinutes(minutesLeft.toLong())
        val h = at.hour % 12
        val km = if (distLeftM < 10_000) "%.1f".format(java.util.Locale.US, distLeftM / 1000.0) else "${(distLeftM / 1000.0).roundToInt()}"
        val left = if (minutesLeft >= 60) "${minutesLeft / 60}H${"%02d".format(minutesLeft % 60)}" else "${minutesLeft}MIN"
        return Protocol.clusterText("${km}KM $left ${if (h == 0) 12 else h}:${"%02d".format(at.minute)}${if (at.hour >= 12) "PM" else "AM"}").take(17)
    }

    /** Heading in plain words a rider gets at a glance: "GOING EAST >>", "<< GOING WEST", "GOING NORTH ^^". */
    fun heading(sector: Int): String = when (sector) {
        0 -> "GOING NORTH ^^"; 1 -> "GOING NORTH EAST"; 2 -> "GOING EAST >>"; 3 -> "GOING SOUTH EAST"
        4 -> "GOING SOUTH"; 5 -> "GOING SOUTH WEST"; 6 -> "<< GOING WEST"; else -> "GOING NORTH WEST"
    }

    /** The ride so far, for when no route runs: "RIDE 12KM 18MIN". */
    /**
     * Where you are, from the phone's geocoder: the road and the area when both fit ("NH 48 KASHIMIRA"), else the road
     * ("ON MG ROAD"), else the area ("IN INDIRANAGAR"). Null when the geocoder named neither.
     */
    fun area(road: String?, place: String?): String? {
        fun clean(t: String?) = t?.replace(".", "")?.let(Protocol::clusterText)?.replace(Regex(" +"), " ")?.trim()?.takeIf { it.isNotBlank() }
        val r = clean(road); val p = clean(place)
        if (r != null && p != null && r.length + 1 + p.length <= 17) return "$r $p"
        if (r != null) return NavParser.fit(if (r.length + 3 <= 17) "ON $r" else NavParser.shorten(r), 17)
        if (p != null) return NavParser.fit(if (p.length + 3 <= 17) "IN $p" else NavParser.shorten(p), 17)
        return null
    }

    fun rideSoFar(km: Double, movingS: Double): String {
        val k = if (km < 10) "%.1f".format(java.util.Locale.US, km) else "${km.roundToInt()}"
        val mins = (movingS / 60).roundToInt()
        return Protocol.clusterText("RIDE ${k}KM ${if (mins >= 60) "${mins / 60}H${"%02d".format(mins % 60)}" else "${mins}MIN"}").take(17)
    }

    /** Far from the next turn: when you'll get there. "ETA 4:08PM 27MIN". */
    fun eta(minutesLeft: Int, now: java.time.LocalTime): String {
        val at = now.plusMinutes(minutesLeft.toLong())
        val h = at.hour % 12; val pm = at.hour >= 12
        val left = if (minutesLeft >= 60) "${minutesLeft / 60}H${"%02d".format(minutesLeft % 60)}" else "${minutesLeft}MIN"
        return Protocol.clusterText("ETA ${if (h == 0) 12 else h}:${"%02d".format(at.minute)}${if (pm) "PM" else "AM"} $left").take(17)
    }
}
