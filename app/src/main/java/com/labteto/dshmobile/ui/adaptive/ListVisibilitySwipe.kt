package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/** Only claims horizontal drags in the intended direction; vertical list scrolling stays untouched. */
internal fun Modifier.listVisibilitySwipe(
    enabled: Boolean,
    opening: Boolean,
    direction: Float,
    onShownChange: (Boolean) -> Unit,
): Modifier = if (!enabled) this else pointerInput(opening, direction, onShownChange) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startDistance = if (direction > 0) down.position.x else size.width - down.position.x
        if (opening && startDistance > 28.dp.toPx()) return@awaitEachGesture
        var travel = 0f
        val expected = if (opening) direction else -direction
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            if (over * expected > 0f) {
                travel += over * expected
                change.consume()
            }
        } ?: return@awaitEachGesture
        val completed = horizontalDrag(drag.id) { change ->
            travel += change.positionChange().x * expected
            change.consume()
        }
        if (completed && travel >= 48.dp.toPx()) onShownChange(opening)
    }
}
