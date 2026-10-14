package io.github.revtale

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames captured from a real RTR 160 4V (connected cluster), engine off. */
class ProtocolTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val captured = listOf(
        "5a11ebebe9eaeaf9eb2f6bebebcbeaebebca13ff" to "5a1100000201011200c48000002001000021f8ff",
        "5a11ebebe9eaeaf9eb2febebebebeaebebca73ff" to "5a1100000201011200c4000000000100002198ff",
        "5a18ebebebebebc2a2eb1090ebebebebeb80d1ff" to "5a180000000000294900fb7b00000000006b3aff",
        "5a19ebebebeb67ebeb67ebebebf3f3ebebebafff" to "5a19000000008c00008c000000181800000044ff",
        "5a12ebebebebebebebebebebebebebebebeb78ff" to "5a120000000000000000000000000000000093ff",
    )

    @Test fun decodesCapturedFramesWithTheirKey() {
        for ((raw, plain) in captured) assertEquals(plain, hex(Protocol.decode(bytes(raw), 0xEB)!!))
    }

    @Test fun wrongKeyFailsChecksum() {
        assertNull(Protocol.decode(bytes(captured[0].first), 0xEA))
    }

    @Test fun xorIsItsOwnInverse() {
        val f = Protocol.riderNameFrame("rider 1")
        assertArrayEquals(f, Protocol.xor(Protocol.xor(f, 0x5C), 0x5C))
    }

    @Test fun keyDetectorFindsKeyWithoutBeingTold() {
        val d = KeyDetector()
        var key: Int? = null
        for ((raw, _) in captured) key = d.offer(bytes(raw)) ?: key
        assertEquals(0xEB, key)
    }

    @Test fun keyDetectorWorksForAnyKey() {
        val d = KeyDetector()
        var key: Int? = null
        for ((_, plain) in captured) key = d.offer(Protocol.xor(bytes(plain), 0x37)) ?: key
        assertEquals(0x37, key)
    }

    @Test fun rememberedKeyIsConfirmedQuickly() {
        val d = KeyDetector(preferred = 0xEB)
        d.offer(bytes(captured[0].first))
        assertEquals(0xEB, d.offer(bytes(captured[2].first)))
    }

    @Test fun textFrameLayout() {
        val f = Protocol.textFrame(Protocol.ID_CUSTOM_LINE, "Hello, world!")
        assertEquals(20, f.size)
        assertEquals(0x5B, f[0].toInt() and 0xFF)
        assertEquals(0x4C, f[1].toInt() and 0xFF)
        assertEquals("HELLO  WORLD!", String(f, 2, 13, Charsets.US_ASCII))
        assertEquals(0xFF, f[19].toInt() and 0xFF)
    }

    @Test fun textIsCutTo17Characters() {
        val f = Protocol.textFrame(Protocol.ID_CUSTOM_LINE, "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        assertEquals("ABCDEFGHIJKLMNOPQ", String(f, 2, 17, Charsets.US_ASCII))
    }

    @Test fun mobileDataLayout() {
        val f = Protocol.mobileDataFrame(Protocol.PhoneStatus(signal = 3, batteryLevel = 4, hour12 = 4, minute = 7,
            second = 30, pm = false, day = 11, month = 10, year2 = 26))
        assertEquals("5b4a347800000407" + "1e00" + "0000" + "0b0a1a" + "00000000ff", hex(f))
    }

    /** Captured from the official app on a real bike: "100 m, right turn", 27 min / 14477 m left. */
    @Test fun navFrameMatchesCapture() {
        val f = Protocol.navFrame(Protocol.NavStep(distToTurnM = 0x32, minutesLeft = 27, distLeftM = 14477, pictogram = 3))
        assertEquals("5a4e0032001b00388d0301010000000000" + "0040ff", hex(f))
        assertTrue(Protocol.checksumOk(f))
    }

    @Test fun maneuverFrameChecksumMatchesCapture() {
        val f = Protocol.maneuverFrame("TURN RIGHT")
        assertEquals(0x6e, f[18].toInt() and 0xFF)
    }

    @Test fun navStartFramesKeepCase() {
        val (a, b) = Protocol.navStartFrames()
        assertEquals("Navigation", String(a, 2, 10, Charsets.US_ASCII))
        assertEquals(0x63, b[1].toInt() and 0xFF)
        assertEquals("Assist On", String(b, 2, 9, Charsets.US_ASCII))
    }

    @Test fun textIsLimitedToClusterFont() {
        assertEquals("12:5 KM/H   OK!", Protocol.clusterText("12.5 km/h - ok!"))
    }

    @Test fun telemetryFieldReader() {
        val p = Protocol.decode(bytes(captured[2].first), 0xEB)
        assertNotNull(p)
        assertTrue(Protocol.u(p!!, 12) in 100..160) // battery voltage x10 (12.3 V here)
    }
}
