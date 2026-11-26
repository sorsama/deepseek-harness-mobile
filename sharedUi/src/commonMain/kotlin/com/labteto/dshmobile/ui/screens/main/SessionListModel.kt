package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.WorkspaceRow
import com.labteto.dshmobile.data.applySavedOrder

/*
 * The drawer's list, as data: which sessions belong together, in what order, and where a dragged row
 * would land. No Compose in here, so every rule that is easy to get subtly wrong (a session moving
 * out of its workspace, an ungrouped session losing its saved place, a fast drag skipping a row) is
 * checked by a plain unit test.
 */

/** [com.labteto.dshmobile.connection.HostsStore.sessionSort]: the workspaces' own row order. */
internal const val SORT_MANUAL = "manual"

/** [com.labteto.dshmobile.connection.HostsStore.sessionSort]: most recently updated first. */
internal const val SORT_UPDATED = "updated"

/** [com.labteto.dshmobile.connection.HostsStore.sessionSort]: grouped under their project. */
internal const val SORT_PROJECT = "project"

/** Block key of the sessions that belong to no workspace. Cannot clash with a workspace id. */
internal const val OTHER_BLOCK_KEY = "~other"

/** Anything the preference holds that this build does not know falls back to manual. */
internal fun normalizeSessionSort(raw: String?): String = when (raw) {
    SORT_UPDATED, SORT_PROJECT -> raw
    else -> SORT_MANUAL
}

/**
 * A run of top-level sessions that share one order: one workspace's sessions, or the ungrouped rest.
 *
 * It is the unit of movement. The harness can reorder a session only among its own workspace's
 * sessions, so a drag never leaves its block, and the block is also what "By project" draws under
 * one header.
 */
internal data class SessionBlock(
    /** The workspace id, or [OTHER_BLOCK_KEY] for sessions with no workspace. */
    val key: String,
    val workspace: WorkspaceRow?,
    val roots: List<SessionRow>,
) {
    /** True for the ungrouped block, whose order lives on the phone rather than on the harness. */
    val isOther: Boolean get() = workspace == null
}

/**
 * Group the listable top-level sessions into [SessionBlock]s, in the order the reader will see them.
 *
 * Workspaces come in the registry's own order and each keeps its `sessionIds` order; a session the
 * workspace lists but the drawer hides (archived, blank, nested under a parent) is simply skipped.
 * Sessions that no workspace lists go into one final block, ordered by [ungroupedOrder] (the
 * reader's saved arrangement), with never-placed sessions in front. A workspace with nothing to show
 * produces no block.
 *
 * @param listable the sessions the drawer may show (not archived, not blank).
 * @param nestedIds sessions that hang under a parent row and so are not roots.
 */
internal fun buildSessionBlocks(
    listable: List<SessionRow>,
    workspaces: List<WorkspaceRow>,
    nestedIds: Set<String>,
    ungroupedOrder: List<String>,
): List<SessionBlock> {
    val byId = listable.associateBy { it.sessionId }
    val claimed = HashSet<String>()
    val blocks = ArrayList<SessionBlock>()
    for (workspace in workspaces) {
        val roots = workspace.sessionIds.mapNotNull { id ->
            val row = byId[id]
            if (row == null || id in nestedIds || !claimed.add(id)) null else row
        }
        if (roots.isNotEmpty()) blocks += SessionBlock(workspace.workspaceId, workspace, roots)
    }
    val inAnyWorkspace = workspaces.flatMapTo(HashSet()) { it.sessionIds }
    val others = listable.filter { it.sessionId !in inAnyWorkspace && it.sessionId !in nestedIds }
    if (others.isNotEmpty()) {
        val order = applySavedOrder(others.map { it.sessionId }, ungroupedOrder)
        val othersById = others.associateBy { it.sessionId }
        blocks += SessionBlock(OTHER_BLOCK_KEY, null, order.mapNotNull { othersById[it] })
    }
    return blocks
}

/** The blocks' rows end to end: the flat "Manual" list. */
internal fun flattenBlocks(blocks: List<SessionBlock>): List<SessionRow> = blocks.flatMap { it.roots }

/**
 * Show each block's roots in a live order while one is being dragged.
 *
 * [liveOrder] holds the dragged block's ids as they stand mid-drag; every other block is untouched.
 */
internal fun withLiveOrder(blocks: List<SessionBlock>, blockKey: String?, liveOrder: List<String>?): List<SessionBlock> {
    if (blockKey == null || liveOrder == null) return blocks
    return blocks.map { block ->
        if (block.key != blockKey) return@map block
        val byId = block.roots.associateBy { it.sessionId }
        block.copy(roots = liveOrder.mapNotNull { byId[it] })
    }
}

/**
 * Show the blocks in a live order while one is being dragged. The ungrouped block stays last: it is
 * not a workspace, so it has no place in the registry order to move to.
 */
internal fun withLiveBlockOrder(blocks: List<SessionBlock>, liveOrder: List<String>?): List<SessionBlock> {
    if (liveOrder == null) return blocks
    val byKey = blocks.associateBy { it.key }
    val placed = liveOrder.toHashSet()
    val moved = liveOrder.mapNotNull { byKey[it] }
    val rest = blocks.filter { it.key !in placed }
    return moved + rest.filter { !it.isOther } + rest.filter { it.isOther }
}

// ---------------------------------------------------------------------------------------------
// Drag arithmetic
// ---------------------------------------------------------------------------------------------

/** The vertical extent of one draggable unit (a row, or a project header and its rows), in pixels. */
internal data class UnitSpan(val top: Float, val bottom: Float) {
    val mid: Float get() = (top + bottom) / 2f
}

/**
 * Where a lifted unit's centre puts it in [order]: one step at a time, as the finger crosses the
 * middle of its neighbour.
 *
 * Comparing centres rather than edges is stable for units of different heights (a row with open
 * subagents under it, say): once swapped, the lifted unit's centre sits a full unit-height beyond
 * the point that would swap it back, so there is no flicker at the boundary. A neighbour with no span
 * (scrolled out of the composed window) is never swapped with; auto-scroll will bring it in.
 *
 * @return the lifted unit's new index in [order] (its current one when nothing moves).
 */
internal fun nextSlotIndex(
    order: List<String>,
    draggedId: String,
    draggedCenter: Float,
    spans: Map<String, UnitSpan>,
): Int {
    val at = order.indexOf(draggedId)
    if (at < 0) return at
    order.getOrNull(at + 1)?.let { spans[it] }?.let { if (draggedCenter > it.mid) return at + 1 }
    order.getOrNull(at - 1)?.let { spans[it] }?.let { if (draggedCenter < it.mid) return at - 1 }
    return at
}

/**
 * The vertical range a held unit of [height] may be *drawn* in: from the top of its block's first
 * slot to the bottom of its last, so its own top runs from the first slot's top to the last slot's
 * bottom minus [height]. A block with no measured end (scrolled out of the composed window) is
 * unbounded on that side.
 */
internal fun blockDrawBounds(order: List<String>, spans: Map<String, UnitSpan>, height: Float): Pair<Float, Float> {
    val low = order.firstOrNull()?.let { spans[it]?.top } ?: Float.NEGATIVE_INFINITY
    val high = order.lastOrNull()?.let { spans[it]?.bottom }?.minus(height) ?: Float.POSITIVE_INFINITY
    return low to high
}

/** Where a held unit is drawn: the finger's position, kept inside its block. */
internal fun clampToBlock(fingerTop: Float, height: Float, order: List<String>, spans: Map<String, UnitSpan>): Float {
    val (low, high) = blockDrawBounds(order, spans, height)
    return if (low <= high) fingerTop.coerceIn(low, high) else fingerTop
}

/**
 * The slot a held unit takes this frame, judged from where the *finger* is, not from where the unit
 * is drawn.
 *
 * The drawn position is clamped to the block ([clampToBlock]), and a unit clamped against the end of
 * its block can only get its centre as far as the last slot's own middle, which is the very point a
 * neighbour of the same height has to be crossed past to swap. Judged from the clamped position a
 * unit could therefore never take the first or the last place, and in a block of two it could never
 * move at all. The finger has no such limit, so pressing past the end swaps all the way to it.
 */
internal fun slotForFinger(
    order: List<String>,
    draggedId: String,
    fingerTop: Float,
    height: Float,
    spans: Map<String, UnitSpan>,
): Int = nextSlotIndex(order, draggedId, fingerTop + height / 2f, spans)

/**
 * How far to scroll this frame while a unit is held near an edge of the list, in pixels.
 *
 * Zero in the middle; ramps up the closer the lifted unit's edge gets to the viewport edge and tops
 * out at [maxStep], and keeps pushing past the edge (a unit dragged beyond the viewport keeps
 * scrolling at full speed). Negative scrolls up.
 */
internal fun autoScrollStep(
    unitTop: Float,
    unitBottom: Float,
    viewportStart: Float,
    viewportEnd: Float,
    edge: Float,
    maxStep: Float,
): Float {
    if (edge <= 0f) return 0f
    val intoTop = (viewportStart + edge) - unitTop
    val intoBottom = unitBottom - (viewportEnd - edge)
    return when {
        intoTop > 0f -> -maxStep * (intoTop / edge).coerceIn(0.1f, 1f)
        intoBottom > 0f -> maxStep * (intoBottom / edge).coerceIn(0.1f, 1f)
        else -> 0f
    }
}
