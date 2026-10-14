package io.github.revtale

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.cos

/** A shareable picture of one ride: the route drawn by speed, distance, time, top speed and the ride score. */
object RideCard {
    private const val W = 1080
    private const val H = 1350

    /** [bikeName]: the rider's bike ("Apache RTR 160 4V") for the heading; the app's name when unknown. */
    fun render(d: RideData, bikeName: String?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(11, 11, 12))
        val red = Color.rgb(229, 32, 46)
        fun paint(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val s = d.summary
        // the rider's bike ("APACHE RTR 160 4V") when they named it, else the app
        c.drawText((bikeName?.takeIf { it.isNotBlank() } ?: "REVTALE").uppercase(), 72f, 120f, paint(40f, red, true))
        val date = runCatching { java.text.SimpleDateFormat("'ride-'yyyyMMdd-HHmmss", java.util.Locale.US).parse(d.file.nameWithoutExtension) }.getOrNull()
        c.drawText(java.text.SimpleDateFormat("EEE d MMM yyyy · h:mm a", java.util.Locale.getDefault()).format(date ?: java.util.Date(d.file.lastModified())),
            72f, 175f, paint(36f, Color.rgb(180, 180, 187)))
        c.drawText("${decimal(s.distanceKm)} km", 72f, 330f, paint(150f, Color.WHITE, true))

        // the route, fitted into a box and coloured by speed (same bands as the in-app map)
        val pts = d.samples.filter { !it.lat.isNaN() && !it.lon.isNaN() }
        val top = 400f; val bottom = 1040f; val left = 72f; val right = W - 72f
        if (pts.size >= 2) {
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val k = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = ((maxLon - minLon) * k).coerceAtLeast(1e-6); val spanY = (maxLat - minLat).coerceAtLeast(1e-6)
            val scale = minOf((right - left) / spanX, (bottom - top) / spanY)
            val ox = left + ((right - left) - spanX * scale) / 2; val oy = top + ((bottom - top) - spanY * scale) / 2
            fun x(p: Sample) = (ox + (p.lon - minLon) * k * scale).toFloat()
            fun y(p: Sample) = (oy + (maxLat - p.lat) * scale).toFloat()
            val colors = intArrayOf(Color.rgb(90, 160, 255), Color.rgb(80, 200, 120), Color.rgb(250, 190, 40), red)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            var path = Path(); var band = -1
            pts.forEachIndexed { i, p ->
                val b = (p.speedKmh / 30).coerceIn(0, 3)
                if (b != band) {
                    if (band >= 0) { path.lineTo(x(p), y(p)); line.color = colors[band]; c.drawPath(path, line) }
                    path = Path().apply { moveTo(x(p), y(p)) }; band = b
                } else path.lineTo(x(p), y(p))
                if (i == pts.lastIndex) { line.color = colors[band]; c.drawPath(path, line) }
            }
            c.drawCircle(x(pts.first()), y(pts.first()), 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            c.drawCircle(x(pts.last()), y(pts.last()), 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })
        } else c.drawText("No GPS on this ride", left, (top + bottom) / 2, paint(40f, Color.rgb(120, 120, 128)))

        val stats = listOfNotNull("Riding time" to duration(s.movingS), "Top km/h" to "${s.topSpeed}", "Avg km/h" to decimal(s.avgMovingSpeed, 0),
            d.score?.let { "Ride score" to "${it.score}" })
        val colW = (right - left) / stats.size
        stats.forEachIndexed { i, (label, value) ->
            val x = left + i * colW
            c.drawText(value, x, 1160f, paint(54f, Color.WHITE, true))
            c.drawText(label.uppercase(), x, 1215f, paint(30f, Color.rgb(160, 160, 168)))
        }
        c.drawText("Recorded with RevTale · independent app, not affiliated with any bike maker", left, 1290f, paint(26f, Color.rgb(110, 110, 118)))
        return bmp
    }
}
