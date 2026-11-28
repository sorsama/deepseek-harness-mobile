package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** How far the reader drags toward older messages before the view tabs fold away. */
private val FOLD_AFTER = 16.dp

/** How far the reader drags back toward the newest message before the tabs come back. */
private val RETURN_AFTER = 8.dp

/**
 * How far from the newest message the reader has to be for the tabs to fold. A little more than the
 * tab row itself: the transcript gains that much room when the row goes, so a reader closer to the
 * end than this would be left with a list that no longer scrolls and no way to bring the tabs back.
 */
private val FOLD_MIN_DISTANCE = 56.dp

/**
 * When the tabs stand aside, as a rule over the reader's scrolling and nothing else: it knows no
 * composables and no list, so it is the same on every platform and can be tested with plain numbers.
 *
 * It looks at how the reader moves, not at where the list happens to be. Hiding the row gives the
 * transcript more height, and a rule that read positions would take its own layout change for a
 * scroll and flicker.
 *
 * [foldAfter] is the travel toward older messages that folds the tabs, [returnAfter] the travel back
 * toward the newest that brings them back, and [minDistance] how far from the newest message the
 * reader must already be for folding to start. All three are in pixels.
 */
internal class TabsCollapseRule(
    private val foldAfter: Float,
    private val returnAfter: Float,
    private val minDistance: Float,
) {
    var collapsed: Boolean = false
        private set

    /** Net travel in the current direction since it last changed: positive toward older, negative toward newer. */
    private var travel = 0f

    /**
     * One step of the reader's scrolling. [delta] is positive toward older messages; [distance] is how
     * far the list now is from the newest message (use [Float.MAX_VALUE] when it is rows away).
     */
    fun onScroll(delta: Float, distance: Float) {
        if (delta == 0f) return
        if (delta > 0f) {
            travel = if (travel < 0f) delta else travel + delta
            if (!collapsed && travel >= foldAfter && distance >= minDistance) collapsed = true
        } else {
            travel = if (travel > 0f) delta else travel + delta
            if (collapsed && -travel >= returnAfter) reveal()
        }
    }

    /** Back to showing the tabs, for the newest message, a new session or a view change. */
    fun reveal() {
        collapsed = false
        travel = 0f
    }
}

/**
 * Whether the bar draws its view tabs. They are always there on the Trajectory and Files views, while
 * a sheet or panel is open over the chat, and when [collapsed] is false.
 */
internal fun chatTabsVisible(collapsed: Boolean, tab: ChatTab, locked: Boolean): Boolean =
    !collapsed || tab != ChatTab.Chat || locked

/**
 * The transcript's scrolling turned into [collapsed]: a nested-scroll listener for the list to sit
 * under, and the state the top bar reads. Folding follows the finger (or wheel, or fling) only, so the
 * jumps the app makes itself (a history pick, a send, following the tail) never move the tabs.
 */
@Stable
internal class ChatTabsCollapse(
    private val listState: LazyListState,
    private val rule: TabsCollapseRule,
) {
    var collapsed by mutableStateOf(false)
        private set

    /** False on the views and states where the tabs never fold; set by [rememberChatTabsCollapse]. */
    var active: Boolean = true

    /**
     * A positive delta is the finger moving down, which in the reversed transcript is toward older
     * messages. The delta is only read, never consumed, so the list scrolls exactly as it would without us.
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (active) {
                val distance = if (listState.firstVisibleItemIndex > 0) Float.MAX_VALUE else listState.firstVisibleItemScrollOffset.toFloat()
                rule.onScroll(available.y, distance)
                collapsed = rule.collapsed
            }
            return Offset.Zero
        }
    }

    fun reveal() {
        rule.reveal()
        collapsed = false
    }
}

/**
 * The tabs' fold for the chat in [listState]. [active] is false where they must stay (another view, a
 * sheet over the chat); [resetKey] changes with the open session. The tabs return when any of those
 * change and whenever the list is back at its newest message, which also covers a transcript too short to scroll.
 */
@Composable
internal fun rememberChatTabsCollapse(listState: LazyListState, active: Boolean, resetKey: Any?): ChatTabsCollapse {
    val density = LocalDensity.current
    val collapse = remember(listState, density.density) {
        with(density) {
            ChatTabsCollapse(
                listState,
                TabsCollapseRule(FOLD_AFTER.toPx(), RETURN_AFTER.toPx(), FOLD_MIN_DISTANCE.toPx()),
            )
        }
    }
    SideEffect { collapse.active = active }
    LaunchedEffect(collapse, active, resetKey) { collapse.reveal() }
    LaunchedEffect(collapse, listState) {
        snapshotFlow { listState.canScrollBackward }.collect { scrolledAway -> if (!scrolledAway) collapse.reveal() }
    }
    return collapse
}
