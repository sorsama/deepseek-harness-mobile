package com.labteto.dshmobile.ui.adaptive

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/** Claims horizontal movement only; position updates on every event and settling only on release. */
internal fun Modifier.listPanelDrag(
    enabled: Boolean,
    direction: Float,
    start: () -> Unit,
    move: (Float) -> Unit,
    finish: (Float) -> Unit,
    cancel: () -> Unit,
): Modifier = composed {
    val currentStart by rememberUpdatedState(start)
    val currentMove by rememberUpdatedState(move)
    val currentFinish by rememberUpdatedState(finish)
    val currentCancel by rememberUpdatedState(cancel)
    if (!enabled) this else pointerInput(direction) {
    val tracker = VelocityTracker()
    var travel = 0f
    var lastTime = 0L
    detectHorizontalDragGestures(
        onDragStart = {
            tracker.resetTracking(); travel = 0f; lastTime = 0L
            currentStart()
        },
        onDragEnd = { currentFinish(tracker.calculateVelocity().x * direction) },
        onDragCancel = { currentCancel() },
    ) { change, amount ->
        // Compose already rebases previousPosition when this node moves in layout.
        // Adding the node's new origin again counts the panel movement twice.
        val delta = amount
        if (lastTime == 0L) tracker.addPosition(change.previousUptimeMillis,
            androidx.compose.ui.geometry.Offset(travel, 0f))
        travel += delta
        lastTime = change.uptimeMillis
        tracker.addPosition(lastTime, androidx.compose.ui.geometry.Offset(travel, 0f))
        change.consume()
        currentMove(delta * direction)
    }
    }
}
