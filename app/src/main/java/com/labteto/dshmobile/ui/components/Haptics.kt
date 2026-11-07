package com.labteto.dshmobile.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.labteto.dshmobile.ui.theme.HapticStrength
import com.labteto.dshmobile.ui.theme.LocalHapticStrength
import com.labteto.dshmobile.ui.theme.LocalHapticsEnabled

/**
 * The app's haptic vocabulary.
 *
 * A phone UI is felt before it is seen, and a flat tap on every control is nearly as bad as no
 * tap at all: if everything buzzes the same way, the buzz stops meaning anything. So the
 * vocabulary is small but distinct, and each entry is tied to a kind of event:
 *
 *  - [tap] — a control took the press (buttons, chips, rows).
 *  - [toggle] — a switch/segment flipped, with a different feel on and off.
 *  - [selection] — the thumb moved through a discrete picker (segments, tabs, sliders).
 *  - [confirm] — something was approved/sent/succeeded.
 *  - [reject] — something was denied/stopped/failed.
 *  - [attention] — the agent needs a person (approval, question, plan review).
 *  - [longPress] — a hold gesture was recognised (message/tool-call actions).
 *  - [tick] — a soft tick for a threshold or an edge.
 *  - [dragStart], [dragCross], [dragDrop] — picking something up, passing a detent, letting go.
 *    For a continuous gesture use [ticker], which throttles and thins the stream.
 *
 * What actually plays is decided per phone, by [HapticPlanner] and [HapticEngine]: composed
 * primitives where the phone supports them, then the predefined effects, a short amplitude-aware
 * pulse, and finally the View's own haptic constants. The user's [HapticStrength] scales or steps
 * every effect. Every call is a no-op when haptics are off in Appearance, so call sites never have
 * to check the setting.
 *
 * Source compatible with the earlier two-argument constructor: strength and engine have defaults.
 */
class DsHaptics(
    private val view: View,
    private val enabled: Boolean,
    private val strength: HapticStrength = HapticStrength.Normal,
    private val engine: HapticEngine = HapticEngine.get(view.context),
) {
    /** A control was pressed. */
    fun tap() = play(HapticEvent.Tap)

    /** A switch or segment changed. [on] gives the two directions a different feel. */
    fun toggle(on: Boolean) = play(if (on) HapticEvent.ToggleOn else HapticEvent.ToggleOff)

    /** A discrete picker moved (segmented control, tab, page). */
    fun selection() = play(HapticEvent.Selection)

    /** An affirmative decision landed: approve, send, connect. */
    fun confirm() = play(HapticEvent.Confirm)

    /** A negative decision landed: reject, stop, disconnect. */
    fun reject() = play(HapticEvent.Reject)

    /** Something arrived that needs the person: an approval, a question, a finished turn. */
    fun attention() = play(HapticEvent.Attention)

    /** A hold gesture was recognised. */
    fun longPress() = play(HapticEvent.LongPress)

    /** A soft tick for crossing a threshold, e.g. a pull-to-refresh latch. */
    fun tick() = play(HapticEvent.Tick)

    /** Something was picked up to be moved. */
    fun dragStart() = play(HapticEvent.DragStart)

    /**
     * A held thing crossed one detent. [weight] (0 to 1) is how much of a full tick to play.
     * Prefer [ticker] for a gesture that can cross many: it throttles and lightens fast movement.
     */
    fun dragCross(weight: Float = 1f) = play(HapticEvent.DragCross, weight)

    /** A held thing was let go, or a continuous control settled. */
    fun dragDrop() = play(HapticEvent.DragDrop)

    /** A fresh tick stream for one gesture. Keep it for the gesture's whole life. */
    fun ticker(): HapticTicker = HapticTicker(play = { event, weight -> play(event, weight) })

    internal fun play(event: HapticEvent, weight: Float = 1f): Boolean {
        if (!enabled) return false
        return runCatching { engine.play(view, event, strength, weight) }.getOrDefault(false)
    }
}

/** The current screen's haptics, already gated on the user's Appearance settings. */
@Composable
fun rememberDsHaptics(): DsHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    val strength = LocalHapticStrength.current
    return remember(view, enabled, strength) { DsHaptics(view, enabled, strength) }
}
