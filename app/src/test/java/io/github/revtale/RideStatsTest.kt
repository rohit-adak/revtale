package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideStatsTest {

    /** Synthetic ride, 1 sample/s: idle, launch through gears 1-3, cruise, brake hard, stall, restart. */
    private fun ride(): List<Sample> {
        val out = ArrayList<Sample>()
        var t = 0L
        fun add(speed: Int, rpm: Int, gear: Int, lat: Double = 12.7, lon: Double = 77.5) {
            out += Sample(t, speed, rpm, gear, 50, lat, lon, 13.8); t += 1000
        }
        repeat(5) { add(0, 1500, 0) }                         // idle in neutral
        listOf(10, 20, 30, 40).forEach { add(it, 4000 + it * 100, 1) }
        listOf(45, 52, 61, 68).forEach { add(it, 6000 + it * 10, 2) }
        repeat(10) { add(70, 6500, 3, 12.7 + it * 0.0002) }   // cruise, moving north
        add(50, 4500, 3); add(30, 3000, 2); add(10, 2000, 1); add(0, 1500, 0)
        add(0, 0, 0)                                          // engine stop
        add(0, 1500, 0)
        return out
    }

    @Test fun basics() {
        val r = RideStats.summarize(ride())
        assertEquals(70, r.topSpeed)
        assertEquals(1, r.engineStops)
        assertTrue(r.idleS >= 5.0)
        assertTrue(r.distanceKm > 0.2)
    }

    @Test fun perGear() {
        val r = RideStats.summarize(ride())
        val g3 = r.gears.first { it.gear == 3 }
        assertEquals(70, g3.topSpeed)
        assertNotNull(g3.kmhPer1000Rpm)
        assertEquals(10.8, g3.kmhPer1000Rpm!!, 0.2)          // 70 / 6.5
        assertEquals(100.0, r.gears.sumOf { it.sharePct }, 0.01)
    }

    @Test fun shiftPointsAndLaunch() {
        val r = RideStats.summarize(ride())
        assertEquals(8000, r.shiftUpRpm[1])                    // left 1st at 40 km/h, 8000 rpm
        assertEquals(7.0, r.best0to60S!!, 0.01)               // 0 at t=4 s, 61 at t=11 s
        assertTrue(r.hardBrakes >= 2)
        assertEquals(20, r.maxDecelKmhPerS)
    }

    @Test fun csvRoundTrip() {
        val s = Sample(123L, 55, 7200, 3, 80, 12.783456, 77.404321, 14.1)
        assertEquals(s, Sample.parse(s.csv()))
    }

    @Test fun atThisPace() {
        assertEquals(30.0, RideStats.etaMinutes(20.0, 40.0)!!, 0.001)
        assertEquals(10.0, RideStats.reachKm(40.0, 15.0), 0.001)
    }

    @Test fun ridingModeRoundTripAndShare() {
        val s = Sample(1000L, 40, 6000, 3, 20, Double.NaN, Double.NaN, 13.9, 2)
        assertEquals(2, Sample.parse(s.csv())?.mode)
        assertEquals(-1, Sample.parse("1000,40,6000,3,20,,,13.9")?.mode) // rides recorded before modes
        assertEquals(2, RideModes.of(0x22)); assertEquals(1, RideModes.of(0x21))
        val ride = (0 until 10).map { Sample(it * 1000L, 30, 5000, 2, 10, mode = if (it < 4) 1 else 2) }
        val modes = RideStats.summarize(ride).modeS
        assertEquals(4.0, modes[1]!!, 0.01); assertEquals(5.0, modes[2]!!, 0.01)
    }

    @Test fun gearIsLowNibble() {
        assertEquals(3, Protocol.gear(0x23)); assertEquals(2, Protocol.gear(0x22)); assertEquals(3, Protocol.gear(0x13))
        assertEquals(0, Protocol.gear(0x00)); assertEquals(5, Protocol.gear(0x05))
        assertEquals(3, Sample.parse("1000,40,6000,35,20,,,13.9")?.gear) // old file with raw byte 0x23
    }

    @Test fun insights() {
        val samples = ride()
        val r = RideStats.summarize(samples)
        val i = RideStats.insights(samples, r.gears)
        assertEquals(0, i.stops)                                  // ends parked: not a traffic stop
        assertEquals(2, i.upshifts); assertEquals(2, i.downshifts) // 1→2→3 and 3→2→1; neutral is not a gear change
        assertEquals(0.0, i.throttleClosedPct!!, 0.01)            // throttle 50 throughout
        assertNotNull(i.best0to40S)
        assertEquals(13.8, i.voltsIdle!!, 0.01)
    }

    @Test fun speedometerErrorAgainstGps() {
        // GPS moves 0.0001375° of latitude a second = 55 km/h while the bike says 60: reads 9% high
        val samples = (0 until 60).map { Sample(it * 1000L, 60, 6000, 3, 30, 12.7 + it * 0.0001375, 77.5, 13.9) }
        val i = RideStats.insights(samples, RideStats.summarize(samples).gears)
        assertEquals(9.0, i.speedoErrorPct!!, 1.5)
    }

    @Test fun clutchPulledIsSeen() {
        // gear 3 at ~10 km/h per 1000 rpm, then rolling at 40 km/h with the engine at idle (lever in)
        val samples = (0 until 40).map { Sample(it * 1000L, 40, 4000, 3, 20) } + (40 until 50).map { Sample(it * 1000L, 40, 1500, 3, 0) }
        val i = RideStats.insights(samples, RideStats.summarize(samples).gears)
        assertEquals(20.0, i.clutchPct!!, 3.0)
    }

    @Test fun trafficStops() {
        val speeds = List(10) { 30 } + List(5) { 0 } + List(10) { 30 } + List(1) { 0 } + List(5) { 30 }
        val samples = speeds.mapIndexed { t, v -> Sample(t * 1000L, v, 3000, 2, 10) }
        val i = RideStats.insights(samples, RideStats.summarize(samples).gears)
        assertEquals(1, i.stops)                                  // the 1 s dip is not a stop
        assertEquals(5.0, i.longestStopS, 0.01)
    }
}
