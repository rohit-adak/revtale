package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class NavParserTest {

    @Test fun typicalGoogleMapsNotification() {
        val n = NavParser.parse("250 m", "Turn left onto MG Road", "12 min · 4.3 km · 10:45 ETA")!!
        assertEquals(Maneuver.LEFT, n.maneuver)
        assertEquals(250, n.distToTurnM)
        assertEquals(12, n.minutesLeft)
        assertEquals(4300, n.distLeftM)
    }

    @Test fun instructionInTitle() {
        val n = NavParser.parse("Turn right toward Station Rd", "1.2 km", "1 hr 5 min · 52 km")!!
        assertEquals(Maneuver.RIGHT, n.maneuver)
        assertEquals(1200, n.distToTurnM)
        assertEquals(65, n.minutesLeft)
        assertEquals(52000, n.distLeftM)
    }

    @Test fun maneuvers() {
        assertEquals(Maneuver.ROUNDABOUT, NavParser.maneuverOf("At the roundabout, take the 2nd exit"))
        assertEquals(Maneuver.UTURN_LEFT, NavParser.maneuverOf("Make a U-turn"))
        assertEquals(Maneuver.SLIGHT_RIGHT, NavParser.maneuverOf("Slight right onto NH 48"))
        assertEquals(Maneuver.KEEP_LEFT, NavParser.maneuverOf("Keep left to continue on WEH"))
        assertEquals(Maneuver.DESTINATION, NavParser.maneuverOf("Your destination is on the right"))
        assertEquals(Maneuver.STRAIGHT, NavParser.maneuverOf("Continue onto SV Road"))
    }

    @Test fun nonNavigationTextIsIgnored() {
        assertNull(NavParser.parse("Location sharing", "You are sharing your location", null))
    }

    @Test fun onlyConfirmedArrowsWithoutOverrides() {
        assertEquals(3, Pictograms.code(Maneuver.RIGHT, emptyMap()))
        assertEquals(0, Pictograms.code(Maneuver.LEFT, emptyMap()))
        assertNull(Pictograms.code(Maneuver.UNKNOWN, emptyMap()))
        assertEquals(12, Pictograms.code(Maneuver.LEFT, mapOf(Maneuver.LEFT to 12)))
        assertEquals(54, Pictograms.heading("Head south"))
        assertEquals(57, Pictograms.heading("Head northwest toward MG Road"))
        assertEquals(51, Pictograms.heading("head north-east"))
        assertNull(Pictograms.heading("Turn left toward MG Road"))
        assertEquals("^ SOUTH", NavParser.helpLine(NavParser.parse("Head south", null, null)!!, 0))
        assertEquals("ARRIVING ON RIGHT", NavParser.helpLine(NavParser.parse("300 m · Destination will be on the right", null, null)!!, 300))
        assertEquals("YOU HAVE ARRIVED", NavParser.helpLine(NavParser.parse("You have arrived", null, null)!!, 0))
        assertEquals("MG ROAD", NavParser.helpLine(NavParser.parse("120 m · Turn right onto MG Road", null, null)!!, 120))
        assertEquals("70M MG ROAD", NavParser.helpLine(NavParser.parse("70 m · Turn right onto MG Road", null, null)!!, 70))
        assertEquals("TURN RIGHT", NavParser.helpLine(NavParser.parse("Turn right", null, null)!!, 0))
        val place = NavParser.parse("70 m · Lake View", null, null)!!
        assertEquals(Maneuver.UNKNOWN, place.maneuver)
        assertEquals("70M LAKE VIEW", NavParser.helpLine(place, 70))
        assertEquals("LAKE VIEW", NavParser.helpLine(place, 150))
        assertEquals("Head south", Pictograms.label(54))
        assertEquals("Sharp left", Pictograms.label(1))
        assertEquals("Fuel", Pictograms.label(81))
        assertNull(Pictograms.label(100))
    }

    @Test fun roadName() {
        assertEquals("MG Road", NavParser.roadOf("Turn right onto MG Road"))
        assertEquals("NH 48", NavParser.roadOf("Slight right onto NH 48"))
        assertEquals("WEH", NavParser.roadOf("Keep left to continue on WEH"))
        assertNull(NavParser.roadOf("Your destination is on the right"))
        assertNull(NavParser.roadOf("Turn left"))
        assertEquals("MG Road", NavParser.parse("250 m", "Turn right onto MG Road", null)?.road)
    }

    @Test fun compass() {
        assertEquals("NORTH", Compass.words(Compass.sector(355f)))
        assertEquals("NORTH EAST", Compass.words(Compass.sector(44f)))
        assertEquals("SOUTH WEST", Compass.words(Compass.sector(225f)))
        assertEquals("W", Compass.short(Compass.sector(-90f)))
        assertEquals(0, Compass.stable(0, 24f))  // just past the N/NE border: stay on N
        assertEquals(1, Compass.stable(0, 35f))  // clearly NE
        assertEquals(1, Compass.stable(-1, 24f)) // nothing shown yet: take it
    }

    @Test fun arrivalTimeGivesMinutesLeft() {
        // real Google Maps notification: the sub-text only has the arrival time
        val at = java.time.LocalTime.of(16, 1)
        val i = NavParser.parse("70 m · Turn right toward MG Road", null, "Arrive 4:08 pm", at)!!
        assertEquals(7, i.minutesLeft)
        assertEquals(70, i.distToTurnM)
        assertEquals("MG Road", i.road)
        assertEquals(30, NavParser.minutesUntil("Arrive 12:15 am", java.time.LocalTime.of(23, 45)))
        val head = NavParser.parse("Head south", null, "Arrive 4:02 pm", at)!!
        assertEquals(Maneuver.STRAIGHT, head.maneuver); assertEquals(null, head.distToTurnM)
    }

    @Test fun helpLineNamesWhereTheStepGoes() {
        fun help(text: String, d: Int) = NavParser.helpLine(NavParser.parse(text, null, null)!!, d)
        // 13 Oct ride: the dash showed "KEEP RIGHT TO TAK"
        assertEquals("KEEP RT FLYOVER", help("300 m · Keep right to take the flyover", 300))
        assertEquals("70M FLYOVER", help("70 m · Keep right to take the flyover", 70))
        assertEquals("RANI LAKSHMI BAI", help("Turn left onto Rani Lakshmi Bai Marg", 0))
        assertEquals("HANUMAN MANDIR", help("700 m · Hanuman mandir Lakeview, Bengaluru East", 700))
        assertEquals("U TURN", help("80 m · Make a U-turn", 150))
        assertEquals("SIDDHI VINAYAK RD", help("Slight right onto Siddhi Vinayak Rd", 0))
        assertTrue(help("90 m · Turn left onto Lokmanya Tilak Main Road Extension", 90).length <= 17)
    }
}
