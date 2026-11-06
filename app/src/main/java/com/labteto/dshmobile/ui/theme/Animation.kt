package com.labteto.dshmobile.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * The app's motion vocabulary, retuned to the "A · Ink" design kit.
 *
 * The kit's curves are product motion rather than Material's defaults: things arrive on
 * `cubic-bezier(.2,.8,.2,1)` and settle, sheets and panels ride the steeper
 * `cubic-bezier(.2,.9,.15,1)`, and direct manipulation (presses, toggles) uses a small
 * overshoot so a tap feels physical instead of animated.
 *
 * The house rule is unchanged: motion explains a change, it does not decorate one. Nothing loops
 * forever except a loading skeleton, and only while something is genuinely loading. Compose
 * automatically shortens or skips these when the system's animation scale is off.
 */
object DsAnimations {
    /** The kit's default ease — `cubic-bezier(.2,.8,.2,1)`. */
    val inkStandard = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** Sheet/panel arrival — `cubic-bezier(.2,.9,.15,1)`. */
    val inkDecelerate = CubicBezierEasing(0.2f, 0.9f, 0.15f, 1f)

    /** A small overshoot for things that pop into place — `cubic-bezier(.3,1.5,.5,1)`. */
    val inkOvershoot = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)

    /** Quick, slightly springy — for direct manipulation that should feel physical. */
    val fastSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /** The default for general transitions: settles without overshooting. */
    val normalSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** Press feedback on buttons, pills and icon buttons (design: scale .97 / 200ms). */
    val pressScale = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh,
    )

    /**
     * Disclosure and dock expansion. Deliberately springy-but-unbouncy: content growing downward
     * with an overshoot reads as a glitch, and a linear tween reads as mechanical.
     */
    val expand: FiniteAnimationSpec<IntSize> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Chevron rotation on a disclosure row. */
    val chevron: AnimationSpec<Float> = tween(180, easing = inkStandard)

    /** Tab and content swaps. Short — a long swap on a large list costs a full relayout. */
    val tabSwap: FiniteAnimationSpec<Float> = tween(180, easing = inkStandard)

    /** Label tint following a tab or segment selection. */
    val tabSwapColor: FiniteAnimationSpec<Color> = tween(180, easing = inkStandard)

    /** Item placement inside a lazy list when rows are inserted, removed, or reordered. */
    val listItem: FiniteAnimationSpec<IntOffset> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Generic opacity change. */
    val fade: FiniteAnimationSpec<Float> = tween(fadeDuration, easing = inkStandard)

    /** Panel slide-in, faster than the platform default so it keeps up with a drag. */
    val panelSlide: FiniteAnimationSpec<IntOffset> = tween(260, easing = inkDecelerate)

    // Kept for call sites written against Material's curves before the kit landed.
    val emphasizedDecelerate = inkStandard
    val emphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /**
     * Screen-to-screen changes (connect, sessions, chat, settings, pairing).
     *
     * The kit's `.push` motion: the incoming screen fades and rises while the outgoing one fades
     * out quickly, so the two are never both fully legible at once.
     */
    val screenEnter: EnterTransition =
        fadeIn(tween(320, delayMillis = 40, easing = inkDecelerate)) +
            slideInVertically(tween(360, easing = inkDecelerate)) { it / 24 } +
            scaleIn(tween(360, easing = inkDecelerate), initialScale = 0.985f)
    val screenExit: ExitTransition =
        fadeOut(tween(140, easing = emphasizedAccelerate)) +
            scaleOut(tween(200, easing = emphasizedAccelerate), targetScale = 1.012f)

    /** A detail screen pushing in from the right (design `.push`). */
    val pushEnter: EnterTransition =
        fadeIn(tween(260, easing = inkDecelerate)) +
            slideInHorizontally(tween(340, easing = inkDecelerate)) { it / 6 }
    val pushExit: ExitTransition =
        fadeOut(tween(160, easing = emphasizedAccelerate)) +
            slideOutHorizontally(tween(220, easing = emphasizedAccelerate)) { -it / 12 }

    /** Cards that take over the bottom of the chat: approvals, questions, plan reviews. */
    val cardEnter: EnterTransition =
        fadeIn(tween(220, easing = inkDecelerate)) +
            slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it / 3 }
    val cardExit: ExitTransition =
        fadeOut(tween(150, easing = emphasizedAccelerate)) +
            shrinkVertically(tween(220, easing = emphasizedAccelerate))

    /** Docks (todo, goal, queue) joining or leaving the stack above the composer. */
    val dockEnter: EnterTransition = fadeIn(tween(200, easing = inkDecelerate)) + expandVertically(expand)
    val dockExit: ExitTransition = fadeOut(tween(140)) + shrinkVertically(expand)

    /** A floating control popping in, like the jump-to-latest button. */
    val popEnter: EnterTransition =
        fadeIn(tween(160)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), initialScale = 0.7f)
    val popExit: ExitTransition = fadeOut(tween(120)) + scaleOut(tween(140, easing = inkStandard), targetScale = 0.7f)

    /** List rows arriving as a screen settles (design `.in` with stagger delays). */
    val rowEnter: EnterTransition =
        fadeIn(tween(300, easing = inkDecelerate)) +
            slideInVertically(tween(340, easing = inkDecelerate)) { it / 8 }

    /** Stagger between list rows, mirroring the kit's `.d1…d10` delays (capped for long lists). */
    fun staggerDelay(index: Int): Int = (index.coerceAtMost(6) * 40)

    /** Scale animation duration for press effects */
    const val scaleDuration = 100

    /** Fade animation duration for opacity changes */
    const val fadeDuration = 150

    /** Standard transition duration for layout changes */
    const val transitionDuration = 200

    /** Scale values for interactive elements */
    object Scale {
        const val normal = 1f

        /** The kit presses every tappable thing to .97. */
        const val pressed = 0.97f
    }
}
