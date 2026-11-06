package com.labteto.dshmobile.ui.components

import android.annotation.SuppressLint
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.labteto.dshmobile.ui.theme.LocalHapticsEnabled

/**
 * The app's haptic vocabulary.
 *
 * A phone UI is felt before it is seen, and a flat tap on every control is nearly as bad as no
 * tap at all: if everything buzzes the same way, the buzz stops meaning anything. So the
 * vocabulary is small but distinct, and each entry is tied to a kind of event:
 *
 *  - [tap] — a control took the press (buttons, chips, rows).
 *  - [toggle] — a switch/segment flipped, with a different tick on and off.
 *  - [selection] — the thumb moved through a discrete picker (segments, tabs, sliders).
 *  - [confirm] — something was approved/sent/succeeded.
 *  - [reject] — something was denied/stopped/failed.
 *  - [attention] — the agent needs a person (approval, question, plan review).
 *  - [longPress] — a hold gesture was recognised (message/tool-call actions).
 *
 * API 30 introduced distinct CONFIRM/REJECT effects; below that the app falls back to a crisp
 * virtual key so the rhythm still lands. Every call is a no-op when the user has turned haptics
 * off in Appearance, so call sites never have to check the setting.
 */
// The API-30/34 constants are compile-time inlined ints, and every use is guarded by a runtime
// version check; lint's InlinedApi warning does not apply to this pattern.
@SuppressLint("InlinedApi")
class DsHaptics(
    private val view: View,
    private val enabled: Boolean,
) {
    /** A control was pressed. */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A switch or segment changed. [on] gives the two directions different texture where possible. */
    fun toggle(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) {
            perform(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        } else {
            perform(if (on) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** A discrete picker moved (segmented control, tab, page). */
    fun selection() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** An affirmative decision landed: approve, send, connect. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** A negative decision landed: reject, stop, disconnect. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    /** Something arrived that needs the person: an approval, a question, a finished turn. */
    fun attention() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A hold gesture was recognised. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** A soft tick for crossing a threshold, e.g. a pull-to-refresh latch. */
    fun tick() = perform(HapticFeedbackConstants.CLOCK_TICK)

    private fun perform(constant: Int) {
        if (!enabled) return
        runCatching { view.performHapticFeedback(constant) }
    }
}

/** The current screen's haptics, already gated on the user's Appearance setting. */
@Composable
fun rememberDsHaptics(): DsHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return remember(view, enabled) { DsHaptics(view, enabled) }
}
