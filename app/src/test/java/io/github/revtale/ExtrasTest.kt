package io.github.revtale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    @Test fun weatherUnionReply() {
        val w = Weather.parseWeatherUnion("""{"status":"200","message":"","device_type":1,"locality_weather_data":
            {"temperature":27.4,"humidity":81.2,"wind_speed":1.1,"wind_direction":250,"rain_intensity":8.1,"rain_accumulation":3,
            "aqi_pm_10":null,"aqi_pm_2_point_5":null}}""")!!
        assertEquals("DEST 27C HVY RAIN", w.clusterLine("DEST"))
        assertEquals("Heavy rain", w.condition)
        assertEquals("27°C · Heavy rain · 81% humidity", w.phoneText())
        // no station in range: every value null
        assertNull(Weather.parseWeatherUnion("""{"status":"200","locality_weather_data":{"temperature":null,"humidity":null,"rain_intensity":null}}"""))
        assertNull(Weather.parseWeatherUnion("""{"status":"failed","message":"Not Authorised!"}"""))
    }

    @Test fun openMeteoReply() {
        val w = Weather.parseOpenMeteo("""{"current":{"time":"2026-10-12T12:00","temperature_2m":31.6,"relative_humidity_2m":60,
            "precipitation":0.0,"wind_speed_10m":3.2}}""")!!
        assertFalse(w.raining)
        assertEquals("HERE 32C DRY", w.clusterLine("HERE"))
    }

    @Test fun placesRoundTrip() {
        val list = listOf(SavedPlace("Home", 12.972, 77.608), SavedPlace("Office", 12.89, 77.61, from = "Home"))
        val back = Places.decode(Places.encode(list))
        assertEquals(list, back)
        assertEquals("Home → Office", back[1].label)
        assertEquals(2, Places.with(back, SavedPlace("Home", 1.0, 2.0)).size)
        assertEquals(emptyList<SavedPlace>(), Places.decode("not json"))
    }

    @Test fun bikesRoundTrip() {
        val l = listOf(SavedBike("11:22:33:44:55:66", "My RTR"), SavedBike("AA:BB", "Friend's"))
        assertEquals(l, Bikes.decode(Bikes.encode(l)))
    }

    @Test fun backupZipOnlyReadsKnownFiles() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val ride = java.io.File(dir, "r.csv").apply { writeText(Sample.CSV_HEADER + "\n1,2,3,1,4,,,,\n") }
        val fuel = java.io.File(dir, "f.csv").apply { writeText(FillUp.CSV_HEADER + "\n") }
        val out = java.io.ByteArrayOutputStream()
        Backup.write(out, listOf("rides/ride-20261012-101500.csv" to ride, "fuel.csv" to fuel, "../evil.txt" to fuel), "{}")
        val back = Backup.read(out.toByteArray().inputStream())
        assertEquals(setOf("rides/ride-20261012-101500.csv", "fuel.csv", "settings.json"), back.keys)
        assertTrue(String(back.getValue("rides/ride-20261012-101500.csv")).startsWith("t_ms"))
        assertFalse(Backup.allowed("rides/../../x.csv"))
        val merged = Backup.mergeFuel(listOf("2,100.0,5.00,100.00,-1,-1", "1,50.0,5.00,100.00,-1,-1"), listOf("2,100.0,5.00,100.00,-1,-1", "3,150.0,4.00,100.00,-1,-1"))
        assertEquals(listOf("1", "2", "3"), merged.map { it.substringBefore(',') })
    }

    private fun summary(km: Double, brakes: Int, launches: Int, highS: Double, movingS: Double = 1800.0) =
        RideSummary(movingS, movingS, km, 80, km / (movingS / 3600), 9500, emptyList(), null, emptyMap(), emptyMap(), emptyMap(),
            emptyList(), 0, 0.0, brakes, launches, 15, 15, highS)

    @Test fun rideScore() {
        val calm = RideScores.of(summary(20.0, 0, 0, 0.0), null)!!
        assertEquals(100, calm.score)
        assertEquals("Silky smooth", calm.label)
        val hard = RideScores.of(summary(10.0, 4, 3, 180.0), null)!!
        // 4 brakes/10 km ×4 = 16, 3 launches ×3 = 9, 10% near redline ×2 = 15 (cap 15)
        assertEquals(60, hard.score)
        assertEquals("Hard brakes", hard.minus.first().first)
        assertEquals(2, hard.tips.size)
        assertNull(RideScores.of(summary(0.5, 0, 0, 0.0), null))
    }

    @Test fun monthlyReport() {
        val cal = java.util.Calendar.getInstance().apply { set(2026, 9, 12, 10, 0) }
        val now = cal.timeInMillis
        val day = 86_400_000L
        val rides = listOf(RideMonthEntry(now - day, 12.0, 1800.0, 70, 80), RideMonthEntry(now - 2 * day, 30.0, 3000.0, 95, 60),
            RideMonthEntry(now - 40 * day, 10.0, 900.0, 50, null))
        val fills = listOf(FillUp(now - day, 1000.0, 5.0, 106.0))
        val r = MonthlyReports.build(rides, fills, now)
        assertEquals(2, r.rides); assertEquals(42.0, r.km, 0.01); assertEquals(30.0, r.longestKm, 0.01)
        assertEquals(95, r.topSpeed); assertEquals(70, r.avgScore); assertEquals(530.0, r.fuelSpend, 0.01)
        assertEquals(10.0, r.prevKm!!, 0.01)
        assertEquals(9, r.month)
    }

    @Test fun clusterLines() {
        assertEquals("12KM 34MIN AVG 41", ClusterLines.trip(12.4, 34 * 60.0, 41.2))
        assertEquals("4:5KM 9MIN AVG 30", ClusterLines.trip(4.5, 9 * 60.0, 30.0))
        assertEquals("82KM 1H35 AVG 52", ClusterLines.trip(82.0, 95 * 60.0, 52.0))
        assertEquals("ETA 4:08PM 7MIN", ClusterLines.eta(7, java.time.LocalTime.of(16, 1)))
        assertEquals("ETA 12:05AM 1H20", ClusterLines.eta(80, java.time.LocalTime.of(22, 45)))
        assertTrue(ClusterLines.eta(80, java.time.LocalTime.of(22, 45)).all { it in Protocol.CLUSTER_GLYPHS })
    }

    @Test fun gpx() {
        val g = Gpx.of(listOf(Sample(0, 36, 3000, 2, 10, 12.96, 77.55), Sample(1000, 0, 0, 0, 0)), "Ride <1>")
        assertTrue(g.contains("<trkpt lat=\"12.960000\" lon=\"77.550000\"><time>1970-01-01T00:00:00Z</time><extensions><speed>10.00</speed>"))
        assertTrue(g.contains("Ride &lt;1&gt;"))
        assertEquals(1, Regex("<trkpt").findAll(g).count())
    }

    @Test fun tankMileage() {
        val t = FuelStats.tanks(listOf(FillUp(1, 1000.0, 5.0, 100.0), FillUp(2, 1200.0, 4.0, 100.0), FillUp(3, 1200.0, 2.0, 100.0)))
        assertEquals(1, t.size)
        assertEquals(50.0, t[0].kmPerL, 0.01)
        assertEquals(2.0, t[0].costPerKm, 0.01)
    }

    @Test fun routeInfo() {
        val r = RouteInfo.parseOsrm("""{"code":"Ok","routes":[{"duration":808.3,"distance":12579.7}]}""")!!
        assertEquals(12.58, r.first, 0.01); assertEquals(13, r.second)
        assertNull(RouteInfo.parseOsrm("""{"code":"NoRoute"}"""))
        val home = Place(12.972, 77.608, 5); val office = Place(12.89, 77.61, 5)
        val routes = listOf(UsualRoute(home, office, 6, 12.4, 34.0, 25.0), UsualRoute(office, home, 5, 12.4, 40.0, 22.0))
        assertEquals(34.0, RouteInfo.usual(routes, 12.9721, 77.6081, 12.8901, 77.6101)!!.avgMinutes, 0.01)
        assertNull(RouteInfo.usual(routes, 13.06, 77.55, 12.89, 77.61))
        assertEquals("TRAFFIC 8MIN LATE", RouteInfo.trafficLine(42, 34))
        assertNull(RouteInfo.trafficLine(36, 34))
        assertEquals("12KM 28MIN 4:29PM", ClusterLines.route(12_400, 28, java.time.LocalTime.of(16, 1)))
        assertEquals("4:5KM 9MIN 4:10PM", ClusterLines.route(4_500, 9, java.time.LocalTime.of(16, 1)))
        assertEquals("ETA 4:29PM 28MIN", ClusterLines.route(null, 28, java.time.LocalTime.of(16, 1)))
    }

    @Test fun awayRide() {
        val before = BikeSnapshot(1000, 12_345.6, 4, 92)
        val t = Away.detect(before, BikeSnapshot(9000, 12_369.0, 2, 104))!!
        assertEquals(23.4, t.km, 0.01); assertEquals(4, t.fuelFrom); assertEquals(2, t.fuelTo); assertTrue(t.topSpeedRose)
        assertNull(Away.detect(before, BikeSnapshot(9000, 12_345.9, 4, 92)))   // odometer jitter
        assertNull(Away.detect(null, BikeSnapshot(9000, 12_369.0, 2, 104)))   // first time: nothing to compare
        assertNull(Away.detect(before, BikeSnapshot(9000, 30_000.0, 2, 104)))  // another bike
        assertEquals(listOf(t), Away.decode(Away.encode(listOf(t))))
    }

    @Test fun mapsShareLinks() {
        val full = "https://www.google.com/maps/place/Central+Library/@12.9756,77.6050,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.96125!4d77.60612!16s"
        assertEquals(12.96125 to 77.60612, MapsLink.coords(full))   // the pin, not the view centre
        assertEquals("Central Library", MapsLink.placeName(full))
        assertEquals(12.9 to 77.5, MapsLink.coords("https://maps.google.com/?q=12.9,77.5"))
        assertEquals(12.9 to 77.5, MapsLink.coords("https://www.google.com/maps/search/?api=1&query=12.9%2C77.5"))
        assertEquals(12.9 to 77.56, MapsLink.coords("https://www.google.com/maps/@12.9,77.56,15z"))
        assertNull(MapsLink.coords("https://maps.app.goo.gl/AbCdEf123"))
        val shared = "Central Library\nMG Road, Bengaluru\nhttps://maps.app.goo.gl/AbCdEf123"
        assertEquals("https://maps.app.goo.gl/AbCdEf123", MapsLink.url(shared))
        assertEquals("Central Library", MapsLink.sharedName(shared))
        assertNull(MapsLink.sharedName("https://maps.app.goo.gl/AbCdEf123"))
    }

    @Test fun widgetText() {
        val now = 10 * 3600_000L
        val nav = WidgetText.of(WidgetText.State.RIDING, now, rideKm = 3.0, navMinutes = 28, navLeftM = 12_400, navDest = "Office")
        assertTrue(nav.headline.startsWith("Arrive "))
        assertEquals("28 min · 12.4 km left · to Office", nav.detail)
        assertEquals("Riding", nav.status)
        val away = AwayTrip(0, now, 23.4, 4, 2, 92, 104, null, null)
        val parked = ParkedSpot(1.0, 2.0, now - 2 * 3600_000L, "MG Road", "B2", null)
        assertEquals("Ridden 23.4 km", WidgetText.of(WidgetText.State.OFF, now, away = away, parked = parked).headline)
        val p = WidgetText.of(WidgetText.State.SLEEPING, now, parked = parked)
        assertEquals("Parked · MG Road", p.headline); assertEquals("2 h ago · B2", p.detail); assertEquals("Asleep", p.status)
        assertEquals("Ready to ride", WidgetText.of(WidgetText.State.WAITING, now).headline)
        assertEquals("■■■■□□", WidgetText.fuelBars(4))
    }

    @Test fun engineHeat() {
        // 10 min crawling in traffic heating up from 100 to 150 °C, then 5 min of open road cooling down
        val traffic = (0 until 600).map { i -> Sample(i * 1000L, if (i % 60 < 40) 0 else 8, 1600, 1, 5, 13.0, 77.60, mode = 1,
            engineC = 100 + i / 12, airC = if (i < 30) 33 else 45) }
        val open = (0 until 300).map { i -> Sample(600_000L + i * 1000L, 50, 5000, 4, 30, 12.97, 77.61, engineC = 150 - i / 10, airC = 38) }
        val h = Heat.of(traffic + open)!!
        assertEquals(150, h.maxC)
        assertEquals(33, h.ambientC)
        assertEquals(1, h.events.size)
        assertEquals(HeatCause.TRAFFIC, h.events[0].cause)
        assertEquals(HeatCause.TRAFFIC, h.verdict)
        // hard riding: high RPM before it got hot
        val hard = (0 until 400).map { i -> Sample(i * 1000L, 90, 8500, 5, 90, engineC = 120 + i / 10, airC = 30) }
        assertEquals(HeatCause.RIDING, Heat.of(hard)!!.events.first().cause)
        // older rides without temperature: nothing to say
        assertNull(Heat.of((0 until 50).map { Sample(it * 1000L, 30, 3000, 3, 10) }))
        assertEquals("ENGINE HOT 158C", Heat.clusterLine(158))
        // the new columns round-trip, old lines still parse
        val s = Sample(1, 2, 3, 1, 4, engineC = 132, airC = 41)
        assertEquals(132, Sample.parse(s.csv())!!.engineC)
        assertEquals(-1, Sample.parse("1,2,3,1,4,,,,")!!.engineC)
    }

    @Test fun unknownFillUpRestartsMileage() {
        // 1000 → fill 5 L; 1200 → a friend filled, amount unknown (0 L marker); 1350 → fill 3 L
        val fills = listOf(FillUp(1, 1000.0, 5.0, 100.0), FillUp(2, 1200.0, 0.0, 100.0), FillUp(3, 1350.0, 3.0, 100.0))
        val t = FuelStats.tanks(fills)
        assertEquals(1, t.size)
        assertEquals(50.0, t[0].kmPerL, 0.01) // 150 km on 3 L, not 350 km on 3 L
    }

    @Test fun idleLines() {
        assertEquals("GOING EAST >>", ClusterLines.heading(Compass.sector(90f)))
        assertEquals("<< GOING WEST", ClusterLines.heading(Compass.sector(270f)))
        assertEquals("RIDE 4:5KM 9MIN", ClusterLines.rideSoFar(4.5, 540.0))
        assertEquals("RIDE 82KM 1H35", ClusterLines.rideSoFar(82.0, 95 * 60.0))
        (0..7).forEach { assertTrue(ClusterLines.heading(it).length <= 17 && ClusterLines.heading(it).all { c -> c in Protocol.CLUSTER_GLYPHS }) }
    }
}
