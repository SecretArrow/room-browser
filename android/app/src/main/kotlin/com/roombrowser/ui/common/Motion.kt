package com.roombrowser.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's motion + touch-feedback vocabulary — the layer that makes state
 * changes feel physical instead of instantaneous.
 *
 * Springs carry the spatial moves (a bar arriving, a banner dropping in) and
 * short tweens carry fades. Every spec here is FINITE by design: the e2e
 * suites drive this UI through UiAutomator, whose waitForIdle only settles
 * when animations end, so nothing on chrome the tests can see may pulse,
 * shimmer or spin forever. [RoomExtras.reducedMotion] skips all of it.
 */
object RoomMotion {
    /** Fades for content swaps: icon crossfades, scrims. */
    const val FADE_MS = 160

    /** Exits finish slightly slower than entrances arrive. */
    const val EXIT_MS = 200

    /**
     * Values that arrive — progress fills, border widths, indicator sizes.
     * A smooth approach with NO overshoot: progress that overshoots reads
     * as broken, not lively.
     */
    fun <T> smooth(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    /**
     * Chrome ARRIVING — a bar sliding up over the page, the pick banner
     * dropping in. A small, quick bounce; springs rather than tweens are
     * what make a surface read as physical rather than animated.
     */
    fun <T> arrive(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )
}

/**
 * Chrome entrance: rise a third of the way up and fade in. Honors the
 * profile's reduced-motion setting by skipping all travel.
 */
fun roomEnter(reducedMotion: Boolean): EnterTransition =
    if (reducedMotion) EnterTransition.None
    else fadeIn(tween(RoomMotion.FADE_MS)) + slideInVertically(
        initialOffsetY = { it / 3 },
        animationSpec = RoomMotion.arrive()
    )

/** Chrome exit: fall and fade, damped — leaving never bounces. */
fun roomExit(reducedMotion: Boolean): ExitTransition =
    if (reducedMotion) ExitTransition.None
    else fadeOut(tween(RoomMotion.EXIT_MS)) + slideOutVertically(
        targetOffsetY = { it / 3 },
        animationSpec = tween(RoomMotion.EXIT_MS, easing = FastOutSlowInEasing)
    )

/**
 * One haptic vocabulary for the whole app (there was none — every tap was
 * silent). [tick] is the everyday chrome tap, [knock] a state transition
 * (recording armed, a run stopped), [confirm] something landed (a pick, a
 * save). All three use the system's predefined strengths, so the user's
 * global haptic preference still rules, and none requires a permission.
 */
class RoomHaptics(private val view: View) {
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun knock() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

@Composable
fun rememberRoomHaptics(): RoomHaptics {
    val view = LocalView.current
    return remember(view) { RoomHaptics(view) }
}
