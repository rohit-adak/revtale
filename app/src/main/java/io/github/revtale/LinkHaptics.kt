package io.github.revtale

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/** Interaction feedback only: never driven by telemetry, scrolling or recomposition. */
val LocalLinkHapticsEnabled = staticCompositionLocalOf { true }

private object HapticPacing {
    var lastPulse = 0L
}

class LinkFeedback internal constructor(private val view: View, private val enabled: State<Boolean>) {
    private fun pulse(effect: Int) {
        if (!enabled.value || !view.isAttachedToWindow || !view.hasWindowFocus()) return
        val now = SystemClock.uptimeMillis()
        // Coalesce nested callbacks and rapid slider changes into a restrained tactile response.
        if (now - HapticPacing.lastPulse < 90L) return
        HapticPacing.lastPulse = now
        // No override flags: Android's touch-feedback preference and hardware capabilities win.
        view.performHapticFeedback(effect)
    }

    fun press() = pulse(HapticFeedbackConstants.CONTEXT_CLICK)
    /** One light tick per tab the pager passes; not paced, so a jump across three tabs gives three ticks. */
    fun tabTick() {
        if (!enabled.value || !view.isAttachedToWindow) return
        HapticPacing.lastPulse = SystemClock.uptimeMillis()
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }
    fun selection() = pulse(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    fun toggle(on: Boolean) = pulse(if (Build.VERSION.SDK_INT >= 34) {
        if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
    } else HapticFeedbackConstants.CONTEXT_CLICK)
}

@Composable
fun rememberLinkFeedback(): LinkFeedback {
    val view = LocalView.current
    val enabled = rememberUpdatedState(LocalLinkHapticsEnabled.current)
    return remember(view) { LinkFeedback(view, enabled) }
}

@Composable
fun hapticAction(action: () -> Unit): () -> Unit {
    val feedback = rememberLinkFeedback()
    return { feedback.press(); action() }
}

@Composable
fun hapticSelection(action: () -> Unit): () -> Unit {
    val feedback = rememberLinkFeedback()
    return { feedback.selection(); action() }
}

@Composable
fun hapticToggle(action: (Boolean) -> Unit): (Boolean) -> Unit {
    val feedback = rememberLinkFeedback()
    return { on -> feedback.toggle(on); action(on) }
}

/** Twenty tactile positions along a slider, with a softer selection tick instead of a buzz. */
@Composable
fun hapticSlider(value: Float, range: ClosedFloatingPointRange<Float> = 0f..1f, action: (Float) -> Unit): (Float) -> Unit {
    val feedback = rememberLinkFeedback()
    fun band(v: Float) = (((v - range.start) / (range.endInclusive - range.start)) * 20).roundToInt()
    val latest = rememberUpdatedState(value)
    return { next ->
        if (band(next) != band(latest.value)) feedback.selection()
        action(next)
    }
}
