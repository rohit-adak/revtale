package io.github.revtale

import kotlin.math.roundToInt

/** One fill-up the rider logged. [odoKm] is the bike's odometer when it was noticed; bars before/after, -1 if unknown. */
data class FillUp(
    val tMs: Long, val odoKm: Double, val liters: Double, val pricePerL: Double,
    val barsFrom: Int = -1, val barsTo: Int = -1
) {
    val amount get() = liters * pricePerL

    fun csv() = "$tMs,${"%.1f".format(java.util.Locale.US, odoKm)},${"%.2f".format(java.util.Locale.US, liters)}," +
        "${"%.2f".format(java.util.Locale.US, pricePerL)},$barsFrom,$barsTo"

    companion object {
        const val CSV_HEADER = "t_ms,odo_km,liters,price_per_l,bars_from,bars_to"
        fun parse(line: String): FillUp? = try {
            val p = line.split(',')
            FillUp(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p[3].toDouble(),
                p.getOrNull(4)?.toIntOrNull() ?: -1, p.getOrNull(5)?.toIntOrNull() ?: -1)
        } catch (_: Exception) { null }

        /** Liters from what the rider typed: either liters directly, or the amount paid at [pricePerL]. */
        fun liters(value: Double, isAmount: Boolean, pricePerL: Double): Double? =
            if (!isAmount) value.takeIf { it > 0 } else if (pricePerL > 0 && value > 0) value / pricePerL else null
    }
}

/**
 * Notices a fill-up from the fuel bars (5A10 byte 7). The gauge flickers (fuel sloshing, side stand), so a new level
 * only counts once it has held for [needMovingS] seconds of riding. Higher than the last known level = refuelled.
 */
class FuelWatcher(known: Int, private val needMovingS: Double = 60.0) {
    /** Last stable level; -1 until the first one is seen. */
    var known = known; private set
    private var candidate = -1
    private var heldS = 0.0

    /** Feed the current bars and the seconds ridden since the last call; returns (from, to) once when a fill-up is seen. */
    fun feed(bars: Int, movingS: Double): Pair<Int, Int>? {
        if (bars < 0) return null
        if (bars != candidate) { candidate = bars; heldS = 0.0 }
        heldS += movingS
        if (heldS < needMovingS || candidate == known) return null
        val before = known
        known = candidate
        return if (before >= 0 && candidate > before) before to candidate else null
    }
}

data class FuelSummary(
    /** Average km per liter over the logged intervals; null until two fill-ups exist. */
    val kmPerL: Double?, val costPerKm: Double?, val spentThisMonth: Double, val litersThisMonth: Double,
    val last: FillUp?, val count: Int, val pricePerL: Double?
)

/** Mileage of one tank: from the previous fill-up to [fill]. */
data class TankMileage(val fill: FillUp, val km: Double, val kmPerL: Double, val costPerKm: Double)

/** Mileage from fill-ups: km between fills over the liters put back in. Pure Kotlin, unit-tested. */
object FuelStats {
    /** km/l of each tank, oldest first (needs the previous fill's odometer). */
    fun tanks(fills: List<FillUp>): List<TankMileage> {
        val f = fills.sortedBy { it.tMs }
        return (1 until f.size).mapNotNull { i ->
            val d = f[i].odoKm - f[i - 1].odoKm
            if (d <= 0 || f[i].liters <= 0) null
            else TankMileage(f[i], d, ((d / f[i].liters) * 10).roundToInt() / 10.0, f[i].amount / d)
        }
    }

    fun summarize(fills: List<FillUp>, now: Long = System.currentTimeMillis()): FuelSummary {
        val f = fills.sortedBy { it.tMs }
        // the fuel added at a fill is what the km since the previous fill burned (most accurate with full-tank fills)
        var km = 0.0; var liters = 0.0
        for (i in 1 until f.size) {
            val d = f[i].odoKm - f[i - 1].odoKm
            if (d > 0 && f[i].liters > 0) { km += d; liters += f[i].liters }
        }
        val kmpl = if (liters > 0 && km > 0) ((km / liters) * 10).roundToInt() / 10.0 else null
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val month = cal.get(java.util.Calendar.YEAR) * 12 + cal.get(java.util.Calendar.MONTH)
        val thisMonth = f.filter {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = it.tMs }
            c.get(java.util.Calendar.YEAR) * 12 + c.get(java.util.Calendar.MONTH) == month
        }
        val price = f.lastOrNull()?.pricePerL
        return FuelSummary(kmpl, if (kmpl != null && price != null) price / kmpl else null,
            thisMonth.sumOf { it.amount }, thisMonth.sumOf { it.liters }, f.lastOrNull(), f.size, price)
    }
}

/** A fill-up the app noticed and hasn't asked about yet. [simulated] = seen in the emulator (never saved). */
data class PendingRefuel(val tMs: Long, val odoKm: Double, val barsFrom: Int, val barsTo: Int, val simulated: Boolean = false) {
    fun pref() = "$tMs,$odoKm,$barsFrom,$barsTo"

    companion object {
        fun fromPref(s: String?): PendingRefuel? = s?.split(',')?.takeIf { it.size == 4 }?.let {
            runCatching { PendingRefuel(it[0].toLong(), it[1].toDouble(), it[2].toInt(), it[3].toInt()) }.getOrNull()
        }
    }
}
