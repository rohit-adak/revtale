package io.github.revtale

import java.time.Instant
import java.time.format.DateTimeFormatter

/** A ride as GPX 1.1 (opens in Strava, Google Earth, Komoot…), with speed as an extension per point. Pure, unit-tested. */
object Gpx {
    fun of(samples: List<Sample>, name: String): String {
        val pts = samples.filter { !it.lat.isNaN() && !it.lon.isNaN() }.sortedBy { it.tMs }
        val iso = DateTimeFormatter.ISO_INSTANT
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"RevTale\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk><name>").append(escape(name)).append("</name><type>motorcycling</type><trkseg>\n")
        for (p in pts) {
            sb.append("    <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time><extensions><speed>%.2f</speed></extensions></trkpt>\n"
                .format(java.util.Locale.US, p.lat, p.lon, iso.format(Instant.ofEpochMilli(p.tMs)), p.speedKmh / 3.6))
        }
        sb.append("  </trkseg></trk>\n</gpx>\n")
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
