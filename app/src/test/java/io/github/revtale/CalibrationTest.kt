package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTest {
    private fun f10(b12: Int, speed: Int = 0) = ByteArray(20).also { it[0] = 0x5A; it[1] = 0x10; it[2] = speed.toByte(); it[11] = b12.toByte() }

    @Test fun findsTheIswitchAndIgnoresSpeed() {
        val f = SwitchFinder(holdMs = 300)
        f.feed(f10(0x80), 0)                 // baseline: engine stopped flag only
        assertNull(f.feed(f10(0x80, speed = 3), 100))
        assertNull(f.feed(f10(0x84), 200))   // i held
        assertEquals("5A10.b12" to 0x84, f.seeing)
        assertNull(f.feed(f10(0x84), 700))
        val s = f.feed(f10(0x80), 800)!!     // let go
        assertEquals(SwitchSignal("5A10.b12", 0x04, 0x04), s)
        // engine running clears 0x80: still matches
        assertTrue(s.matches(f10(0x04)))
        assertFalse(s.matches(f10(0x02)))     // the ringing-call reading isn't the i switch
    }

    @Test fun aBlipIsNotAPress() {
        val f = SwitchFinder(holdMs = 300)
        f.feed(f10(0), 0); f.feed(f10(4), 100)
        assertNull(f.feed(f10(0), 150))
    }

    @Test fun profileRoundTrips() {
        val c = BikeCalibration(1L, "Apache", mapOf(BikeCalibration.INFO to SwitchSignal("5A10.b12", 4, 4)), fuelMax = 8,
            nameShown = true, lineShown = null, arrows = mapOf("LEFT" to true, "ROUNDABOUT" to false), gears = mapOf(0 to 0, 9 to 6))
        assertEquals(c, BikeCalibration.parse(c.json()))
    }

    @Test fun calibratedGearsWin() {
        try {
            Protocol.gearMap = mapOf(9 to 6)
            assertEquals(6, Protocol.gear(0x29))          // flags in the high nibble, sixth gear reported as 9
            assertEquals(3, Protocol.gear(0x23))          // unmapped: the nibble
            assertEquals(6, Protocol.gear(6, mapped = false))
        } finally { Protocol.gearMap = emptyMap() }
    }

    @Test fun areaLine() {
        assertEquals("NH 48 KASHIMIRA", ClusterLines.area("NH 48", "Kashimira"))
        assertEquals("ON MG ROAD", ClusterLines.area("M.G. Road", "Shivajinagar Bengaluru"))
        assertEquals("IN INDIRANAGAR", ClusterLines.area(null, "Indiranagar"))
        assertTrue(ClusterLines.area("Lokmanya Tilak Main Road Extension", null)!!.length <= 17)
        assertNull(ClusterLines.area(null, " "))
    }

    @Test fun arrowPickedOrDroppedSurvivesSaving() {
        val c = BikeCalibration(1L, "bike", arrows = mapOf("ROUNDABOUT" to true, "UTURN_LEFT" to false, "LEFT" to true),
            arrowCodes = mapOf("ROUNDABOUT" to 71))
        val back = BikeCalibration.parse(c.json())!!
        assertEquals(mapOf("ROUNDABOUT" to 71), back.arrowCodes)
        assertTrue(back.arrowOk(Maneuver.ROUNDABOUT))
        assertFalse(back.arrowOk(Maneuver.UTURN_LEFT))
        assertTrue(back.arrowOk(Maneuver.RIGHT))
        // saved before arrow picks existed
        assertEquals(emptyMap<String, Int>(), BikeCalibration.parse("""{"at":1,"bike":"b"}""")!!.arrowCodes)
    }
}
