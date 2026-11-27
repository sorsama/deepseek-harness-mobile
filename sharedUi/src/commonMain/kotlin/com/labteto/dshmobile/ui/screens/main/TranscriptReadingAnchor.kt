package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Preserve a visible Markdown block when the reverse-layout viewport changes width. */
internal class TranscriptReadingAnchor {
    private val blocks = mutableMapOf<Any, LayoutCoordinates>()
    private data class Anchor(val key: Any, val top: Float)
    private var anchor: Anchor? = null
    private var width = 0
    private var pending: Job? = null
    private var refresh: (() -> Unit)? = null

    fun record(key: Any, coordinates: LayoutCoordinates) {
        blocks[key] = coordinates
        refresh?.invoke()
    }

    fun schedule(scope: CoroutineScope, viewport: LayoutCoordinates, state: LazyListState, isFollowing: () -> Boolean) {
        refresh = { schedule(scope, viewport, state, isFollowing) }
        if (pending?.isActive == true) return
        pending = scope.launch {
            withFrameNanos { }
            if (!viewport.isAttached) return@launch
            blocks.entries.removeAll { !it.value.isAttached }
            val viewportTop = viewport.positionInRoot().y
            val height = viewport.size.height
            val newWidth = viewport.size.width
            val following = isFollowing()
            val previous = anchor
            val widthChanged = width != 0 && width != newWidth
            var retained: Anchor? = null
            if (!following && !state.isScrollInProgress && widthChanged && previous != null) {
                val block = blocks[previous.key]
                if (block != null) {
                    val delta = previous.top - (block.positionInRoot().y - viewportTop)
                    val consumed = if (abs(delta) >= 1f) state.dispatchRawDelta(delta) else 0f
                    val correctedTop = previous.top - delta + consumed
                    if (correctedTop < height && correctedTop + block.size.height > 0) {
                        retained = Anchor(previous.key, correctedTop)
                    }
                }
            }
            width = newWidth
            anchor = if (following) null else retained ?: blocks.entries.mapNotNull { (key, block) ->
                val top = block.positionInRoot().y - viewportTop
                if (top < height && top + block.size.height > 0) Anchor(key, top) else null
            }.minByOrNull { abs(it.top - height / 2f) }
        }
    }
}
