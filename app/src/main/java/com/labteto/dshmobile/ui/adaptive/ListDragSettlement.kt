package com.labteto.dshmobile.ui.adaptive

/** Logical dp and dp/s. Drag position remains continuous; only release chooses an anchor. */
internal fun settleListDrag(width: Float, velocity: Float, minimum: Float, previous: Float, maximum: Float): Float = when {
    velocity <= -800f && width <= minimum -> 0f
    velocity >= 800f -> maxOf(width, previous, minimum).coerceAtMost(maximum)
    width <= (minimum - 48f).coerceAtLeast(0f) -> 0f
    else -> width.coerceIn(minimum, maximum)
}
