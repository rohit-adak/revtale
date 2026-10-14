package io.github.revtale

import kotlin.math.roundToInt

/**
 * Engine heat from the bike's own sensors (5A18 b9 engine °C, b8 intake air °C). On Rohit's RTR 160 4V a 16-minute
 * crawl through traffic went from 71 to 145 °C while the intake read 33 → 57 °C, so the reading is the hot head
 * of an air/oil-cooled engine, not coolant: 120–140 °C is normal riding, [HOT] and up is worth knowing about.
 */
enum class HeatCause(val who: String) {
    TRAFFIC("Traffic, not you"), RIDING("Your riding"), WEATHER("Hot weather"), LONG("A long, steady ride")
}

/** A stretch at or above [Heat.HOT]: when, where it started, how hot it got, and what most likely caused it. */
data class HeatEvent(
    val startMs: Long, val endMs: Long, val peakC: Int, val lat: Double, val lon: Double,
    val cause: HeatCause, val evidence: String
) {
    val minutes get() = (endMs - startMs) / 60_000.0
}

data class RideHeat(
    val maxC: Int, val maxAtMs: Long, val avgMovingC: Int,
    /** Outside temperature estimate: the intake air while the engine was still cold (≈ the air around the bike). */
    val ambientC: Int?,
    val hotMinutes: Double, val events: List<HeatEvent>,
    /** Over the whole ride: who or what made it hot; null when it never got hot. */
    val verdict: HeatCause?
)

object Heat {
    /** Hot enough to mention (°C on this sensor). */
    const val HOT = 145
    /** Hot enough to warn on the cluster. */
    const val VERY_HOT = 155
    /** A hot stretch must last this long to count (a reading can spike). */
    private const val MIN_EVENT_MS = 30_000L
    /** How far back to look for the cause. */
    private const val LOOK_BACK_MS = 5 * 60_000L

    /** Pure, unit-tested. Null for rides recorded before engine temperature was logged. */
    fun of(samples: List<Sample>): RideHeat? {
        val s = samples.filter { it.engineC in 1..250 }.sortedBy { it.tMs }
        if (s.size < 10) return null
        val max = s.maxBy { it.engineC }
        val moving = s.filter { it.speedKmh > 0 }
        // intake air with the engine still cool ≈ outside air; the lowest such reading
        val ambient = s.filter { it.airC in 1..70 && it.engineC < 90 }.minOfOrNull { it.airC }
            ?: s.filter { it.airC in 1..70 }.take(30).minOfOrNull { it.airC }

        val events = mutableListOf<HeatEvent>()
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            val a = s[start]; val b = s[end]
            if (b.tMs - a.tMs >= MIN_EVENT_MS) {
                val peak = s.subList(start, end + 1).maxOf { it.engineC }
                val at = s.subList(start, end + 1).firstOrNull { !it.lat.isNaN() } ?: s.subList(0, start + 1).lastOrNull { !it.lat.isNaN() }
                val (cause, why) = cause(s.filter { it.tMs in (a.tMs - LOOK_BACK_MS)..a.tMs }, ambient)
                events += HeatEvent(a.tMs, b.tMs, peak, at?.lat ?: Double.NaN, at?.lon ?: Double.NaN, cause, why)
            }
            start = -1
        }
        for (i in s.indices) {
            if (s[i].engineC >= HOT) { if (start < 0) start = i } else close(i - 1)
        }
        close(s.lastIndex)

        val hotMin = s.indices.filter { s[it].engineC >= HOT }.sumOf { i ->
            if (i + 1 < s.size) ((s[i + 1].tMs - s[i].tMs) / 60_000.0).coerceAtMost(5.0 / 60) else 0.0
        }
        val verdict = events.groupBy { it.cause }.maxByOrNull { (_, l) -> l.sumOf { it.minutes } }?.key
        return RideHeat(max.engineC, max.tMs, moving.map { it.engineC }.average().takeIf { !it.isNaN() }?.roundToInt() ?: max.engineC,
            ambient, hotMin, events, verdict)
    }

    /**
     * What heated it up, from the minutes before: hard riding (high RPM) is "you"; standing and crawling is
     * traffic (air-cooled engines need airflow); a hot day otherwise; else just a long ride.
     */
    fun cause(before: List<Sample>, ambientC: Int?): Pair<HeatCause, String> {
        if (before.size < 2) return HeatCause.LONG to "Heat built up over the ride."
        val dt = before.indices.map { i -> if (i + 1 < before.size) ((before[i + 1].tMs - before[i].tMs) / 1000.0).coerceIn(0.0, 5.0) else 0.0 }
        val total = dt.sum().coerceAtLeast(1.0)
        val slow = before.indices.filter { before[it].speedKmh < 10 }.sumOf { dt[it] } / total
        val hard = before.indices.filter { before[it].rpm >= 7000 }.sumOf { dt[it] } / total
        val mins = (total / 60).roundToInt().coerceAtLeast(1)
        return when {
            hard >= 0.25 -> HeatCause.RIDING to "Above 7,000 RPM for ${(hard * 100).roundToInt()}% of the $mins min before."
            slow >= 0.5 -> HeatCause.TRAFFIC to "Standing or crawling under 10 km/h for ${(slow * 100).roundToInt()}% of the $mins min before."
            ambientC != null && ambientC >= 35 -> HeatCause.WEATHER to "About $ambientC °C outside."
            else -> HeatCause.LONG to "Steady riding; the heat built up over time."
        }
    }

    /** On the cluster when the engine stays very hot: "ENGINE HOT 158C" (no '°' in the cluster font). */
    fun clusterLine(c: Int) = Protocol.clusterText("ENGINE HOT ${c}C").take(17)
}
