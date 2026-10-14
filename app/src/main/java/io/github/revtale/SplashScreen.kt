package io.github.revtale

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import kotlin.random.Random

private val Night = Color(0xFF0B0B0C)
private val White = Color(0xFFF4F4F5)

// Timeline (ms). Fast, like a streaming-app sting.
private const val T_SPEED = 750f     // 0 -> TOP km/h, streaks speed up with it, GEAR UP flows in
private const val T_FLAG = 380f      // chequered flag wipe, starts at T_SPEED
private const val T_LOGO = 950f      // logo + wordmark appear behind the flag
private const val T_HOLD = 1750f
private const val T_END = 2050f      // push-in and fade to the app
private const val TOP = 312

private class Streak(val y: Float, val len: Float, val speed: Float, val phase: Float, val width: Float, val red: Boolean)

/** Speed 0..1 at time t: accelerating (ease-in), the same curve drives the number and the streaks. */
private fun speedFrac(t: Float): Float = (t / T_SPEED).coerceIn(0f, 1f).pow(1.6f)

/** Distance the streaks have travelled (screen widths): the integral of the speed curve, so they move exactly as fast as the number says. */
private fun travelled(t: Float): Float {
    val vMax = 9f                         // screen widths per second at top speed
    val tc = t.coerceAtMost(T_SPEED) / 1000f
    val T = T_SPEED / 1000f
    val accel = vMax * T * (tc / T).pow(2.6f) / 2.6f
    val cruise = vMax * (max(0f, t - T_SPEED) / 1000f)
    return accel + cruise
}

/** Intro: MotoGP-style speed run with GEAR UP flowing in, chequered-flag wipe, then the RevTale logo. */
@Composable
fun ClusterSplash(onDone: () -> Unit) {
    val clock = remember { Animatable(0f) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        clock.animateTo(T_END, tween(T_END.toInt(), easing = LinearEasing))
        done()
    }
    val t = clock.value
    val v = speedFrac(t)
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val streaks = remember {
        val r = Random(7)
        List(60) { Streak(r.nextFloat(), 0.1f + r.nextFloat() * 0.35f, 0.7f + r.nextFloat() * 0.6f, r.nextFloat() * 1.6f,
            1f + r.nextFloat() * 3f, r.nextFloat() < 0.35f) }
    }

    val exit = ((t - T_HOLD) / (T_END - T_HOLD)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()
        .graphicsLayer { alpha = 1f - FastOutLinearInEasing.transform(exit); val s = 1f + exit * 0.25f; scaleX = s; scaleY = s }
        .background(Night)
        .clickable(remember { MutableInteractionSource() }, null) { done() }
        .semantics { contentDescription = "RevTale. Tap to skip." },
        contentAlignment = Alignment.Center) {

        // Streaks: velocity and motion-blur length follow the speed number; they fade once the logo is up.
        val streakAlpha = if (t < T_LOGO) 0.25f + 0.75f * v else (1f - (t - T_LOGO) / 400f).coerceIn(0f, 1f) * 0.6f
        Canvas(Modifier.fillMaxSize()) {
            val d = travelled(t)
            streaks.forEach { s ->
                val len = size.width * s.len * (0.15f + 1.4f * v)
                val x = size.width * (1.2f - ((s.phase + d * s.speed) % 1.6f))
                val y = size.height * s.y
                drawLine(Brush.horizontalGradient(listOf((if (s.red) RacingRed else White).copy(alpha = 0.9f * streakAlpha), Color.Transparent),
                    startX = x, endX = x + len), Offset(x, y), Offset(x + len, y), strokeWidth = s.width.dp.toPx(), cap = StrokeCap.Round)
            }
        }

        if (t < T_LOGO) {
            val kmh = (v * TOP).roundToInt()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$kmh", color = if (kmh > 280) RacingRed else White, fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Black, fontSize = 140.sp, lineHeight = 140.sp, letterSpacing = (-6).sp)
                Text("KM/H", color = White.copy(alpha = .6f), fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black,
                    fontSize = 20.sp, letterSpacing = 6.sp)
                Spacer(Modifier.height(36.dp))
                // GEAR UP flows in from the left as the speed builds, like the cluster's welcome text.
                val flow = EaseOutCubic.transform((t / T_SPEED).coerceIn(0f, 1f))
                Text("GEAR UP", color = ForkGold, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black,
                    fontSize = 52.sp, letterSpacing = 3.sp, maxLines = 1, softWrap = false,
                    modifier = Modifier.graphicsLayer { translationX = (flow - 1f) * screenW.toPx() })
            }
        } else {
            // Logo: gauge arc draws, needle sweeps to the redline, then the wordmark with a light sweep.
            val lt = t - T_LOGO
            val arc = EaseOutCubic.transform((lt / 300f).coerceIn(0f, 1f))
            val needle = EaseOutBack.transform((lt / 420f).coerceIn(0f, 1f))
            val word = ((lt - 150f) / 250f).coerceIn(0f, 1f)
            val sheen = ((lt - 350f) / 450f).coerceIn(0f, 1f)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                AppLogo(Modifier.size(150.dp), arc, needle)
                Text("REVTALE", color = White, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Black,
                    fontSize = 56.sp, letterSpacing = 2.sp,
                    modifier = Modifier
                        .graphicsLayer { alpha = word; translationX = (1f - EaseOutCubic.transform(word)) * -80.dp.toPx() }
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val x = -size.width * 0.5f + sheen * size.width * 2f
                            drawRect(Brush.linearGradient(listOf(Color.Transparent, RacingRed.copy(alpha = .9f), Color.Transparent),
                                start = Offset(x - 120f, 0f), end = Offset(x + 120f, size.height)), blendMode = BlendMode.SrcAtop)
                        })
            }
        }

        val flag = (t - T_SPEED) / T_FLAG
        if (flag in 0f..1f) Canvas(Modifier.fillMaxSize()) { chequeredFlag(FastOutSlowInEasing.transform(flag), t) }
    }
}

/**
 * The RevTale mark: a speedometer arc with a redline, a needle pinned near the top and a gold hub.
 * Matches res/drawable/ic_launcher_foreground.xml. [arc] and [needle] (0..1) animate it in.
 */
@Composable
fun AppLogo(modifier: Modifier = Modifier, arc: Float = 1f, needle: Float = 1f) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val r = size.minDimension / 2 - stroke
        val c = Offset(size.width / 2, size.height / 2 + size.minDimension * 0.04f)
        val tl = Offset(c.x - r, c.y - r)
        // Dial 135° -> 405° (clockwise through the top); last 55° is the redline.
        drawArc(White.copy(alpha = .9f), 135f, 215f * arc, false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
        if (arc > 0.8f) drawArc(RacingRed, 350f, 55f * ((arc - 0.8f) / 0.2f), false, tl, Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Math.toRadians((135.0 + 185.0 * needle))
        val tip = Offset(c.x + (r * 0.82f) * cos(a).toFloat(), c.y + (r * 0.82f) * sin(a).toFloat())
        drawLine(RacingRed, c, tip, strokeWidth = stroke * 0.7f, cap = StrokeCap.Round)
        drawCircle(ForkGold, stroke * 0.85f, c)
    }
}

/** A waving chequered flag, taller than the screen, sweeping left to right. */
private fun DrawScope.chequeredFlag(p: Float, t: Float) {
    val cell = size.width / 6f
    val cols = 9
    val rows = (size.height / cell).toInt() + 3
    val flagW = cols * cell
    val x0 = -flagW + p * (size.width + flagW * 1.2f)
    for (c in 0 until cols) {
        val wave = sin((c / cols.toFloat()) * 2 * PI + t / 70.0).toFloat() * cell * 0.4f
        val x = x0 + c * cell
        drawRect(Night, Offset(x, wave - cell), Size(cell * 1.35f, size.height + cell * 2))
        for (r in -1 until rows) {
            if ((c + r + 2) % 2 != 0) continue
            val y = r * cell + wave
            drawPath(Path().apply {
                moveTo(x + cell * .3f, y); lineTo(x + cell * 1.3f, y)
                lineTo(x + cell, y + cell); lineTo(x, y + cell); close()
            }, White)
        }
    }
}
