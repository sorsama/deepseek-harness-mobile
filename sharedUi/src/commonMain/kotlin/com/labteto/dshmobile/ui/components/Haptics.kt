package com.labteto.dshmobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.labteto.dshmobile.ui.theme.HapticStrength
import com.labteto.dshmobile.ui.theme.LocalHapticStrength
import com.labteto.dshmobile.ui.theme.LocalHapticsEnabled

/** What the app means, whichever phone it runs on. */
enum class HapticEvent {
    /** A control took the press. */
    Tap,

    /** A switch turned on. */
    ToggleOn,

    /** A switch turned off. */
    ToggleOff,

    /** A discrete choice moved (segment, chip, radio row). */
    Selection,

    /** An affirmative decision landed. */
    Confirm,

    /** A negative decision landed. */
    Reject,

    /** The agent needs a person. */
    Attention,

    /** A hold gesture was recognised. */
    LongPress,

    /** A soft tick for a threshold or an edge. */
    Tick,

    /** Something was picked up to be moved. */
    DragStart,

    /** A held thing crossed one detent (a slot, a hue step, a page). */
    DragCross,

    /** A held thing was let go, or a continuous control settled. */
    DragDrop,
}

/**
 * The platform half of haptics: turns an event the app means into whatever this phone can play.
 *
 * Android's is the engine the app has always had (`HapticEngine`: it probes the vibrator, plans per
 * tier and plays with touch attributes). iOS maps the same vocabulary onto `UIImpactFeedbackGenerator`,
 * `UISelectionFeedbackGenerator` and `UINotificationFeedbackGenerator`. Neither is allowed to throw
 * into a gesture, and [DsHaptics] guards the call anyway.
 */
interface HapticPlayer {
    /**
     * Play [event] at the user's [strength]; [weight] (0 to 1) says how much of a full effect, for
     * the ticks of a fast gesture.
     *
     * @return whether anything was handed to the phone.
     */
    fun play(event: HapticEvent, strength: HapticStrength, weight: Float): Boolean
}

/**
 * Where a platform plugs its [HapticPlayer] in.
 *
 * A composable [rememberPlayer] rather than a plain player because Android's player belongs to the
 * `View` of the window that is composing: a dialog or a bottom sheet has a window of its own, and the
 * engine's last-resort tier plays through that window's view. The host provides one platform at the
 * root of its composition ([LocalHapticPlatform]); anything composed without a host gets no haptics.
 */
interface HapticPlatform {
    @Composable
    fun rememberPlayer(): HapticPlayer
}

/** The platform used when a host provides none: nothing is played. */
private object SilentHapticPlatform : HapticPlatform {
    private val silent = object : HapticPlayer {
        override fun play(event: HapticEvent, strength: HapticStrength, weight: Float): Boolean = false
    }

    @Composable
    override fun rememberPlayer(): HapticPlayer = silent
}

/** The host's haptics. Provided once at the root: `AppRoot` on Android, the entry point on iOS. */
val LocalHapticPlatform = staticCompositionLocalOf<HapticPlatform> { SilentHapticPlatform }

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
 * What actually plays is decided per phone, by the [HapticPlayer] the host provides: on Android
 * composed primitives where the phone supports them, then the predefined effects, a short
 * amplitude-aware pulse, and finally the View's own haptic constants. The user's [HapticStrength]
 * scales or steps every effect. Every call is a no-op when haptics are off in Appearance, so call
 * sites never have to check the setting.
 */
class DsHaptics(
    private val player: HapticPlayer,
    private val enabled: Boolean,
    private val strength: HapticStrength = HapticStrength.Normal,
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
        return runCatching { player.play(event, strength, weight) }.getOrDefault(false)
    }
}

/** The current screen's haptics, already gated on the user's Appearance settings. */
@Composable
fun rememberDsHaptics(): DsHaptics {
    val player = LocalHapticPlatform.current.rememberPlayer()
    val enabled = LocalHapticsEnabled.current
    val strength = LocalHapticStrength.current
    return remember(player, enabled, strength) { DsHaptics(player, enabled, strength) }
}
