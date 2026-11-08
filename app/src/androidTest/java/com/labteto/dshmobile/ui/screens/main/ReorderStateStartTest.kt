package com.labteto.dshmobile.ui.screens.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.labteto.dshmobile.ui.components.AndroidHapticPlayer
import com.labteto.dshmobile.ui.components.DsHaptics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.flow.first

/**
 * Picking a unit up must work for a whole project as well as for a session row.
 *
 * A project header and a session row have different lazy-list keys, and the drawer says which by the
 * group the unit is held in. [ReorderState.start] used to look the unit up *before* it set the group,
 * so a project was looked up under a session's key, was not found, and the grip did nothing: in
 * "Group by project" a project could never be dragged. Rows were unaffected, because the stale group
 * (none) happens to key a row correctly.
 *
 * The list here has a project header, a session row under it, and a second project, keyed the way
 * the drawer keys them.
 */
class ReorderStateStartTest {

    @get:Rule val compose = createComposeRule()

    private class Picked(val draggedId: String?, val groupKey: String?, val liveOrder: List<String>?)

    private fun pickUp(id: String, group: String, order: List<String>): Picked {
        var picked: Picked? = null
        compose.setContent {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val view = LocalView.current
            val state = remember {
                ReorderState(listState, scope, DsHaptics(AndroidHapticPlayer(view), enabled = false), edgePx = 50f, maxStepPx = 10f)
            }
            state.itemKeyOf = { g, unit -> if (g == PROJECTS_GROUP) "project-$unit" else "row-$unit" }
            state.keysOf = { unit -> listOf("project-$unit", "row-$unit") }
            LazyColumn(state = listState, modifier = Modifier.height(600.dp)) {
                item(key = "project-w1") { Box(Modifier.fillMaxWidth().height(48.dp)) }
                item(key = "row-s1") { Box(Modifier.fillMaxWidth().height(72.dp)) }
                item(key = "row-s2") { Box(Modifier.fillMaxWidth().height(72.dp)) }
                item(key = "project-w2") { Box(Modifier.fillMaxWidth().height(48.dp)) }
            }
            LaunchedEffect(Unit) {
                // Pick up only once the list has been laid out: the unit is found by its measured item.
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
                state.start(id, group, order)
                picked = Picked(state.draggedId, state.groupKey, state.liveOrder)
                state.cancel()
            }
        }
        compose.waitForIdle()
        return checkNotNull(picked) { "the pick-up never ran" }
    }

    @Test
    fun aProjectHeaderCanBePickedUp() {
        val picked = pickUp("w1", PROJECTS_GROUP, listOf("w1", "w2"))
        assertEquals("w1", picked.draggedId)
        assertEquals(PROJECTS_GROUP, picked.groupKey)
        assertEquals(listOf("w1", "w2"), picked.liveOrder)
    }

    @Test
    fun aSessionRowCanBePickedUp() {
        val picked = pickUp("s1", "w1", listOf("s1", "s2"))
        assertEquals("s1", picked.draggedId)
        assertEquals("w1", picked.groupKey)
    }

    @Test
    fun aUnitThatIsNotInTheListIsNotPickedUp() {
        val picked = pickUp("nope", PROJECTS_GROUP, listOf("nope"))
        assertNull(picked.draggedId)
        assertNull(picked.groupKey)
    }
}
