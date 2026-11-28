package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.AndroidHapticPlayer
import com.labteto.dshmobile.ui.components.DsHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A session row lifted from its hold menu has no finger on it, and it has to stay where it is.
 *
 * Lifting by the old grip always had a finger on the unit, so a unit lifted near the foot of the list
 * rightly scrolled the list on. Lifted from the menu, the same place would scroll the list by itself
 * with nobody asking, so the edge only counts once a finger has moved the unit.
 */
class ReorderArmedTest {

    @get:Rule val compose = createComposeRule()

    private val ids = List(30) { "s$it" }
    private var listState: LazyListState? = null
    private var reorder: ReorderState? = null

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val view = LocalView.current
            val density = LocalDensity.current
            val unit = remember {
                ReorderState(
                    state, scope, DsHaptics(AndroidHapticPlayer(view), enabled = false),
                    edgePx = with(density) { 72.dp.toPx() }, maxStepPx = with(density) { 14.dp.toPx() },
                )
            }
            unit.itemKeyOf = { _, id -> "row-$id" }
            unit.keysOf = { id -> listOf("row-$id") }
            listState = state
            reorder = unit
            Box(Modifier.height(400.dp)) {
                LazyColumn(state = state) {
                    items(ids.size, key = { "row-${ids[it]}" }) { Box(Modifier.fillMaxWidth().height(72.dp)) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun scrolled(): Boolean {
        val state = checkNotNull(listState)
        return state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0
    }

    @Test
    fun aRowLiftedNearTheFootOfTheListDoesNotScrollItByItself() {
        show()
        // The fifth row ends 40dp from the foot of a 400dp list: inside the edge zone.
        compose.runOnIdle { checkNotNull(reorder).start("s4", "g", ids) }
        compose.mainClock.advanceTimeBy(800)
        assertNotNull("the row is lifted", reorder!!.draggedId)
        assertTrue("it stays where it was lifted", !scrolled())
        compose.runOnIdle { checkNotNull(reorder).cancel() }
    }

    @Test
    fun onceAFingerMovesTheLiftedRowTheEdgeScrollsTheListAsBefore() {
        show()
        compose.runOnIdle {
            checkNotNull(reorder).start("s4", "g", ids)
            checkNotNull(reorder).dragBy(1f)
        }
        compose.mainClock.advanceTimeBy(800)
        assertTrue("a unit pressed against the foot scrolls the list on", scrolled())
        compose.runOnIdle { checkNotNull(reorder).cancel() }
    }

    @Test
    fun letGoWithoutADragChangesNothing() {
        show()
        var dropped: DropResult? = null
        compose.runOnIdle {
            val state = checkNotNull(reorder)
            state.onDropped = { dropped = it }
            state.start("s1", "g", ids)
            state.onGestureEnd()
        }
        compose.mainClock.advanceTimeBy(400)
        val result = dropped
        assertNotNull("letting go reports the drop", result)
        assertEquals("s1", result!!.id)
        assertTrue("nothing moved, so nothing is sent to the harness", !result.changed)
        assertEquals(ids, result.order)
        assertNull("the unit is put down", reorder!!.draggedId)
    }
}
