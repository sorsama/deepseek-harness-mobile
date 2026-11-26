package com.labteto.dshmobile.ui.screens.main

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.labteto.dshmobile.ui.components.DsHaptics
import com.labteto.dshmobile.ui.theme.DsAnimations
import com.labteto.dshmobile.ui.theme.DsShapes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/*
 * Drag-to-reorder for the sessions list, built directly on LazyListState.
 *
 * Why not a library: the list is not one homogeneous column. A "unit" being moved is a session row
 * (with open subagents hanging under its neighbours), or a whole project (a header and its rows);
 * date headings, a pinned group and a hint sit between them; and the move is confined to one block
 * and confirmed by a host round trip. The generic reorderable libraries assume a flat list of
 * equal items and own the data order, which is the opposite of what is needed here. The core is
 * about 200 lines: one lifted unit, a live order that swaps one neighbour at a time as the finger
 * crosses its middle, and a frame loop that scrolls when the finger is held near an edge.
 *
 * Coexistence with the hold menu: the drag starts on a visible grip handle that is *outside* the
 * row's tap-and-hold area, not on the row itself. Holding the row still opens its menu, a tap on it
 * opens the session, and neither can start a drag; the grip can only drag, and a tap on it does
 * nothing.
 */

/** The reorder group key for dragging whole projects, as opposed to the sessions inside one project. */
internal const val PROJECTS_GROUP = "~projects"

/** What dropping a lifted unit produced. */
internal data class DropResult(
    val id: String,
    /** The block the unit was held in (see [ReorderState.groupKey]). */
    val group: String,
    /** The order the unit's siblings are now in. */
    val order: List<String>,
    /** True when [order] differs from the one the drag started with. */
    val changed: Boolean,
    /** True when the finger let go outside the unit's block, where the unit cannot go. */
    val releasedOutside: Boolean,
)

/**
 * The state of one drag at a time on one list.
 *
 * Reads that the UI draws with ([draggedId], [liveOrder], [translationOf]) are snapshot state, so a
 * lifted row redraws itself every frame without recomposing the list.
 *
 * @param edgePx how close to the top or bottom edge of the list the lifted unit must get before the
 * list scrolls.
 * @param maxStepPx the fastest the list scrolls per frame.
 */
@Stable
internal class ReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptics: DsHaptics,
    private val edgePx: Float,
    private val maxStepPx: Float,
) {
    /**
     * One tick stream for the whole life of this state, so its throttle survives from one slot
     * crossing to the next. A fast drag over several rows is then a quick roll of light ticks
     * instead of a click per row, and letting go lands with a firmer one.
     */
    private val ticker = haptics.ticker()

    /** The unit being held (a session id, or a project key), or null when nothing is. */
    var draggedId: String? by mutableStateOf(null)
        private set

    /** The key of the block the held unit may not leave (a workspace id, or the "other" key). */
    var groupKey: String? by mutableStateOf(null)
        private set

    /** The siblings' ids in the order they would take if the unit were dropped now. */
    var liveOrder: List<String>? by mutableStateOf(null)
        private set

    /** True while the finger is pressing past the edge of the block, where the unit cannot follow. */
    var atBoundary: Boolean by mutableStateOf(false)
        private set

    /**
     * The lazy-list item key of a unit's first item (what the lifted unit is positioned by), given the
     * group the unit is held in and its id. The group is an argument, not read from [groupKey], because
     * [start] has to find the unit *before* the drag has a group: a project header and a session row
     * are keyed differently, and a lookup that reached for the old group looked a project up as if it
     * were a session and never found it.
     */
    var itemKeyOf: (group: String?, id: String) -> String = { _, id -> id }

    /** Every lazy-list item key that belongs to a unit: the row and its open subagents, or a header and its rows. */
    var keysOf: (String) -> List<String> = { listOf(it) }

    val active: Boolean get() = draggedId != null

    /**
     * Whether something in block [blockKey] can still be reached by the unit being held. Everything
     * that cannot is dimmed, which is how "a session cannot leave its workspace" shows: only what
     * the held row may be placed among stays bright. While projects are being moved, every project
     * can be reached but the ungrouped one, which is not a workspace and stays last.
     */
    fun reaches(blockKey: String?): Boolean = when (groupKey) {
        null -> true
        PROJECTS_GROUP -> blockKey != null && blockKey != OTHER_BLOCK_KEY
        else -> blockKey == groupKey
    }

    /** What the screen does with a finished drag. Set by the screen each composition, so it always sees fresh state. */
    var onDropped: (DropResult) -> Unit = {}

    private var originalOrder: List<String> = emptyList()
    private var fingerTop by mutableFloatStateOf(0f)
    private var settleValue: Float? by mutableStateOf(null)
    private var loop: Job? = null

    /** The unit's own lazy items right now. */
    private fun liveInfo(key: String) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /**
     * How far, in pixels, the held unit is drawn from where layout put it.
     *
     * Layout always places the unit at its current slot in [liveOrder]; the finger decides where it
     * is *drawn*, clamped to its block. Taking the difference every frame is what keeps the unit
     * under the finger when a neighbour swaps (its slot jumps, the translation absorbs the jump) and
     * while the list scrolls.
     */
    fun translationOf(id: String): Float {
        if (id != draggedId) return 0f
        settleValue?.let { return it }
        val info = liveInfo(itemKeyOf(groupKey, id)) ?: return 0f
        return drawnTop(info.size.toFloat(), spans()) - info.offset
    }

    private fun spans(): Map<String, UnitSpan> {
        val order = liveOrder ?: return emptyMap()
        val byKey = listState.layoutInfo.visibleItemsInfo.associateBy { it.key }
        val out = HashMap<String, UnitSpan>(order.size)
        for (id in order) {
            // The held unit's own extent is its one lifted item, wherever its children are.
            val keys = if (id == draggedId) listOf(itemKeyOf(groupKey, id)) else keysOf(id)
            val infos = keys.mapNotNull { byKey[it] }
            if (infos.isEmpty()) continue
            out[id] = UnitSpan(
                top = infos.minOf { it.offset }.toFloat(),
                bottom = infos.maxOf { it.offset + it.size }.toFloat(),
            )
        }
        return out
    }

    /** Where the finger wants the unit, kept inside the block: the first slot's top to the last slot's bottom. */
    private fun bounds(spans: Map<String, UnitSpan>, height: Float): Pair<Float, Float> =
        blockDrawBounds(liveOrder.orEmpty(), spans, height)

    private fun drawnTop(height: Float, spans: Map<String, UnitSpan>): Float =
        clampToBlock(fingerTop, height, liveOrder.orEmpty(), spans)

    /**
     * Lift [id]. [order] is its siblings' ids (itself included) as they stand; [group] is the block
     * it may not leave.
     */
    fun start(id: String, group: String, order: List<String>) {
        if (active) return
        val info = liveInfo(itemKeyOf(group, id)) ?: return
        draggedId = id
        groupKey = group
        originalOrder = order
        liveOrder = order
        fingerTop = info.offset.toFloat()
        atBoundary = false
        settleValue = null
        ticker.reset()
        haptics.dragStart()
        loop = scope.launch {
            while (isActive && draggedId != null) {
                withFrameNanos { }
                if (!frame()) break
            }
        }
    }

    /** The finger moved. */
    fun dragBy(dy: Float) {
        if (active && settleValue == null) fingerTop += dy
    }

    /** One frame: scroll if the finger is at an edge, then swap with whichever neighbour it has crossed. */
    private suspend fun frame(): Boolean {
        val id = draggedId ?: return false
        val order = liveOrder ?: return false
        val info = liveInfo(itemKeyOf(groupKey, id)) ?: run { abort(); return false }
        val height = info.size.toFloat()
        val spans = spans()
        val at = order.indexOf(id)

        // Scroll only toward a neighbour that exists: pressed against the end of the block there is
        // nothing further to bring into view, and scrolling on would carry the held unit away.
        var step = autoScrollStep(
            unitTop = fingerTop,
            unitBottom = fingerTop + height,
            viewportStart = listState.layoutInfo.viewportStartOffset.toFloat(),
            viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat(),
            edge = edgePx,
            maxStep = maxStepPx,
        )
        if ((step < 0f && at <= 0) || (step > 0f && at >= order.lastIndex)) step = 0f
        if (step != 0f) listState.scrollBy(step)

        // The slot follows the finger, not the clamped drawn position: see [slotForFinger].
        val next = slotForFinger(order, id, fingerTop, height, spans)
        if (next >= 0 && next != at) {
            liveOrder = order.toMutableList().apply {
                removeAt(at)
                add(next, id)
            }
            ticker.cross()
        }

        val (low, high) = bounds(spans, height)
        val outside = low <= high && (fingerTop < low - BOUNDARY_SLOP_PX || fingerTop > high + BOUNDARY_SLOP_PX)
        if (outside && !atBoundary) haptics.tick()
        atBoundary = outside
        return true
    }

    /** The finger lifted: drop the unit into its slot and hand the result to [onDropped]. */
    fun onGestureEnd() {
        drop()?.let(onDropped)
    }

    /** Drops the unit into its slot and reports what changed. */
    private fun drop(): DropResult? {
        val id = draggedId ?: return null
        if (settleValue != null) return null
        loop?.cancel()
        val order = liveOrder ?: originalOrder
        val result = DropResult(
            id = id,
            group = groupKey.orEmpty(),
            order = order,
            changed = order != originalOrder,
            releasedOutside = atBoundary,
        )
        ticker.settle()
        settle(translationOf(id))
        return result
    }

    /** The gesture was taken away (the list changed underneath it): put everything back. */
    fun cancel() {
        val id = draggedId ?: return
        if (settleValue != null) return
        loop?.cancel()
        liveOrder = originalOrder
        settle(translationOf(id))
    }

    private fun abort() {
        loop?.cancel()
        clear()
    }

    private fun settle(from: Float) {
        settleValue = from
        scope.launch {
            Animatable(from).animateTo(0f, tween(SETTLE_MS, easing = DsAnimations.inkDecelerate)) {
                settleValue = value
            }
            clear()
        }
    }

    private fun clear() {
        draggedId = null
        groupKey = null
        liveOrder = null
        settleValue = null
        atBoundary = false
    }

    private companion object {
        /** How far past the block's edge the finger has to be before it counts as pressing against it. */
        const val BOUNDARY_SLOP_PX = 6f

        const val SETTLE_MS = 180
    }
}

/**
 * The drag gesture, for a grip handle.
 *
 * [onStart] lifts the unit (it knows the unit's id and block); the rest follows the finger. Drags
 * are immediate rather than after a long press, because the handle exists to be dragged: a press on
 * it can mean nothing else. The pointer input consumes the movement, so the list's own scroll does
 * not also start.
 */
@Composable
internal fun Modifier.reorderHandle(state: ReorderState, onStart: () -> Unit): Modifier {
    val currentStart by rememberUpdatedState(onStart)
    return pointerInput(state) {
        detectDragGestures(
            onDragStart = { currentStart() },
            onDrag = { change, amount ->
                change.consume()
                state.dragBy(amount.y)
            },
            onDragEnd = { state.onGestureEnd() },
            onDragCancel = { state.cancel() },
        )
    }
}

/**
 * How a held unit is drawn and where, and how the rest of the list stands back.
 *
 * The held unit rises (a small scale-up and a shadow), is raised above its neighbours, and follows
 * the finger by [ReorderState.translationOf]. Units outside the held one's block are dimmed, which is
 * how the one-workspace limit shows: only what can still be reached stays bright.
 *
 * @param blockKey the block this unit belongs to; null for something with no block (a date heading).
 */
@Composable
internal fun Modifier.reorderPresentation(
    state: ReorderState,
    unitId: String?,
    blockKey: String?,
    shape: Shape = DsShapes.row,
    liftElevation: Dp = 10.dp,
): Modifier {
    val lifted = unitId != null && state.draggedId == unitId
    val outOfBlock = state.active && !state.reaches(blockKey)
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "reorderLift",
    )
    val dim by animateFloatAsState(
        targetValue = if (outOfBlock) DIMMED_ALPHA else 1f,
        animationSpec = tween(160),
        label = "reorderDim",
    )
    val shadow = with(LocalDensity.current) { liftElevation.toPx() }
    return this
        .then(if (lifted) Modifier.zIndex(1f) else Modifier)
        .graphicsLayer {
            val scale = 1f + 0.025f * lift
            scaleX = scale
            scaleY = scale
            alpha = dim
            shadowElevation = shadow * lift
            this.shape = shape
            clip = false
            translationY = if (unitId != null) state.translationOf(unitId) else 0f
        }
}

/** The fraction of full brightness for rows the held unit cannot reach. */
private const val DIMMED_ALPHA = 0.38f
