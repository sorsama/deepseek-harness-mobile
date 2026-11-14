package com.labteto.dshmobile.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Lets a tap anywhere in a region clear a text selection made in it (issue #90).
 *
 * Every piece of selectable text in the transcript is its own `SelectionContainer`: one per
 * paragraph of a reply, one in each expanded Thinking panel, one in a diff. A container releases its
 * selection in only two ways. A tap inside its own bounds does it, and so does losing focus. A tap
 * on the next paragraph, on the gap between two rows, on a card header or on any other row is
 * outside the container's bounds and does not move focus either, so the highlight, the handles and
 * the Copy / Select all toolbar stayed up until something else happened to take focus.
 *
 * A selection holds focus while it is live, so the region's own gesture observer ends it the way the
 * platform would: it watches for a plain tap and, if something under the region has focus, clears
 * it. The container sees the focus go and releases the selection and the toolbar with it.
 *
 * The observer never consumes anything. It looks at the `Initial` pass, ahead of the children, and
 * only reads, so the same tap still toggles a card, presses a button, opens a link or shows a
 * message's actions. Scrolling and holding are left alone too: a press that moves past touch slop,
 * or is held for as long as a long press takes, is not a tap, so starting a selection (which is a
 * long press) cannot end it. The selection handles and the toolbar live in their own windows and
 * never reach this observer at all.
 *
 * Nothing is cleared while nothing under the region has focus. That matters because the composer
 * is a sibling of the transcript, not a part of it: a tap on a reply must not take the keyboard
 * away from a draft, and with no selection live it does not.
 */
@Composable
fun Modifier.clearSelectionOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    // Read only inside the gesture handler, so it is a plain holder rather than state: writing it
    // must not recompose the region on every focus change.
    val probe = remember { FocusProbe() }
    return this
        .onFocusChanged { probe.focusedInside = it.hasFocus }
        .pointerInput(focusManager) {
            // Far enough back that the first tap is never "the second of a pair", and far enough
            // from Long.MIN_VALUE that subtracting it from an uptime cannot overflow.
            var previousTapUptime = Long.MIN_VALUE / 2
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val longPressMs = viewConfiguration.longPressTimeoutMillis
                var releasedAt: Long? = null
                var dx = 0f
                var dy = 0f
                while (releasedAt == null) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    // A second finger makes it a pinch or a two-finger scroll, never a tap.
                    if (event.changes.size > 1) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    dx = change.position.x - down.position.x
                    dy = change.position.y - down.position.y
                    if (!isDismissTap(dx, dy, change.uptimeMillis - down.uptimeMillis, slop, longPressMs)) break
                    if (change.changedToUpIgnoreConsumed()) releasedAt = change.uptimeMillis
                }
                val up = releasedAt ?: return@awaitEachGesture
                val followsPreviousTap = isDoubleTapFollowUp(
                    down.uptimeMillis - previousTapUptime,
                    viewConfiguration.doubleTapTimeoutMillis,
                )
                previousTapUptime = up
                if (!followsPreviousTap && probe.focusedInside) focusManager.clearFocus()
            }
        }
}

private class FocusProbe {
    var focusedInside = false
}

/**
 * Whether a press that has moved ([dx], [dy]) pixels over [elapsedMs] is still a plain tap: held
 * for less than a long press and not moved past touch slop. Anything else is a scroll, a drag or
 * the start of a long press, and must not end a selection.
 */
internal fun isDismissTap(dx: Float, dy: Float, elapsedMs: Long, touchSlop: Float, longPressMs: Long): Boolean =
    elapsedMs < longPressMs && dx * dx + dy * dy <= touchSlop * touchSlop

/**
 * Whether a tap that came [sincePreviousTapMs] after the last one is the second half of a double
 * click. A mouse double click selects the word under the pointer on its second press, and clearing
 * focus on that release would take the selection straight back. The first click of the pair has
 * already had its say.
 */
internal fun isDoubleTapFollowUp(sincePreviousTapMs: Long, doubleTapTimeoutMs: Long): Boolean =
    sincePreviousTapMs in 0..doubleTapTimeoutMs
