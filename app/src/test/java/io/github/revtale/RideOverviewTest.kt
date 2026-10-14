package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideOverviewTest {
    /** A 10-minute ride from (lat0, lon0) to (lat1, lon1) at [kmh] in [gear]. */
    private fun ride(startMs: Long, lat0: Double, lon0: Double, lat1: Double, lon1: Double, kmh: Int, gear: Int) =
        (0..600).map { i ->
            val f = i / 600.0
            Sample(startMs + i * 1000L, kmh, kmh * 1000 / 10, gear, 30, lat0 + (lat1 - lat0) * f, lon0 + (lon1 - lon0) * f)
        }

    @Test fun usualRoutesGearsAndParking() {
        val home = 12.9720 to 77.6080; val office = 12.8900 to 77.6100; val gym = 13.0100 to 77.5900
        val day = 86_400_000L
        val rides = listOf(
            ride(0, home.first, home.second, office.first, office.second, 40, 3),
            ride(day, home.first + 0.0005, home.second, office.first, office.second + 0.0005, 45, 3),
            ride(2 * day, home.first, home.second, office.first, office.second, 50, 4),
            ride(3 * day, home.first, home.second, gym.first, gym.second, 30, 2))
        val o = RideOverviews.build(rides)
        assertEquals(4, o.rides)
        val top = o.routes.first()
        assertEquals(3, top.count)                                    // home → office, three times
        assertEquals(home.first, top.from.lat, 0.002)
        assertEquals(office.first, top.to.lat, 0.002)
        assertEquals(listOf(2, 3, 4), o.gears.map { it.gear })
        assertTrue(o.gears.first { it.gear == 3 }.avgSpeed in 40..45)
        assertEquals(gym.first, o.parked!!.lat, 0.0001)                  // newest ride ended at the gym
        assertEquals(50, o.topSpeed)
        assertEquals(home.first, o.places.first().lat, 0.002)          // most visited place
    }
}
