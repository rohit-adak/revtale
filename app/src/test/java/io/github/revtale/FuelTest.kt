package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuelTest {

    @Test fun fillUpNeedsAMinuteOfRiding() {
        val w = FuelWatcher(known = 3)
        repeat(30) { assertNull(w.feed(6, 1.0)) }               // 30 s at 6 bars: not yet
        repeat(10) { assertNull(w.feed(6, 0.0)) }               // standing still doesn't count
        var seen: Pair<Int, Int>? = null
        repeat(31) { w.feed(6, 1.0)?.let { seen = it } }
        assertEquals(3 to 6, seen)
        assertEquals(6, w.known)
        repeat(120) { assertNull(w.feed(6, 1.0)) }              // asked once
    }

    @Test fun flickerAndFirstReadingAreNotFillUps() {
        val w = FuelWatcher(known = 6)
        repeat(3) { assertNull(w.feed(7, 1.0)) }                 // 6 → 7 for 3 s (real log: sloshing)
        repeat(120) { assertNull(w.feed(6, 1.0)) }
        val fresh = FuelWatcher(known = -1)
        repeat(120) { assertNull(fresh.feed(4, 1.0)) }           // first level ever seen just becomes known
        assertEquals(4, fresh.known)
        repeat(120) { assertNull(fresh.feed(3, 1.0)) }           // using fuel is not a fill-up
        assertEquals(3, fresh.known)
    }

    @Test fun mileageAndCost() {
        val day = 86_400_000L
        val now = java.util.Calendar.getInstance().apply { set(2026, 9, 20, 12, 0) }.timeInMillis
        val fills = listOf(
            FillUp(now - 20 * day, 4000.0, 8.0, 105.0),
            FillUp(now - 10 * day, 4300.0, 6.0, 105.0),             // 300 km on 6 L
            FillUp(now - day, 4700.0, 9.0, 106.0))                   // 400 km on 9 L
        val s = FuelStats.summarize(fills, now)
        assertEquals(46.7, s.kmPerL!!, 0.05)                       // 700 km / 15 L
        assertEquals(106 / 46.7, s.costPerKm!!, 0.01)
        assertEquals(2, fills.count { it.tMs >= now - 19 * day })
        assertEquals(10.0, FillUp.liters(1050.0, true, 105.0)!!, 0.001)
        assertEquals(4.5, FillUp.liters(4.5, false, 105.0)!!, 0.001)
        assertEquals(fills[1], FillUp.parse(fills[1].csv()))
    }
}
