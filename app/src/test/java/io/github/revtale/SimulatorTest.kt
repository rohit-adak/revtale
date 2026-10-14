package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun silentUntilHandshake() {
        val bike = SimBike()
        bike.step(1.0)
        assertTrue(bike.frames().isEmpty())
        bike.awake = true
        assertEquals(4, bike.frames().size)
    }

    @Test fun framesDecodeLikeTheRealBike() {
        val bike = SimBike().apply { awake = true; auto = false; targetKmh = 40; mode = 2 }
        repeat(40) { bike.step(0.25) }
        val detector = KeyDetector()
        val decoded = HashMap<String, String>()
        repeat(3) {
            bike.step(0.25)
            for (f in bike.frames()) {
                val raw = Protocol.xor(f, SimBike.KEY)
                detector.offer(raw)
                detector.key?.let { k -> Protocol.decode(raw, k)?.let { decoded[hex(it).take(4).uppercase()] = hex(it) } }
            }
        }
        assertEquals(SimBike.KEY, detector.key)
        val live = LiveTelemetry.decode(decoded)
        assertEquals(40, live.speed)
        assertEquals(3, live.gear)                     // 28–42 km/h → 3rd
        assertEquals(2, live.mode)
        assertTrue((live.rpm ?: 0) in 4000..5000)      // 40 / 8.6 per 1000
    }

    @Test fun clusterReadsWhatThePhoneSends() {
        val c = VirtualCluster()
        c.apply(Protocol.riderNameFrame("ROHIT"))
        c.apply(Protocol.textFrame(Protocol.ID_CUSTOM_LINE, "NORTH EAST"))
        c.apply(Protocol.mobileDataFrame(Protocol.PhoneStatus(3, 4, hour12 = 9, minute = 5, second = 0, pm = true,
            day = 11, month = 10, year2 = 26, unreadSms = 2)))
        assertEquals("ROHIT", c.view.riderName)
        assertEquals("NORTH EAST", c.view.line)
        assertEquals("9:05 PM", c.view.clock)
        assertEquals(2, c.view.unread); assertEquals(3, c.view.signal); assertEquals(4, c.view.phoneBattery)

        val step = Protocol.NavStep(250, 12, 4300, 3, prefix = 1)
        c.apply(Protocol.navFrame(step)); c.apply(Protocol.maneuverFrame("MG ROAD"))
        assertTrue(c.view.navOn)
        assertEquals(250, c.view.navDistM); assertEquals(3, c.view.navPicto); assertEquals("MG ROAD", c.view.navText)
        c.apply(Protocol.navFrame(step.copy(navStatus = 0)))
        assertFalse(c.view.navOn)

        c.apply(Protocol.textFrame(0x43, "MOM"))
        assertEquals("Caller", c.view.popupLabel); assertEquals("MOM", c.view.popupText)
        c.apply(Protocol.feedbackFrame(7, "HI"))
        assertEquals(7, c.view.feedbackId); assertEquals("HI", c.view.line)
    }
}
