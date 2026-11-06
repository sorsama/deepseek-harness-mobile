package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the drawer groups, orders and moves sessions: the rules behind Manual and By project. */
class SessionListModelTest {

    private fun session(id: String) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = false,
        parentSessionId = null,
        origin = null,
        cwd = null,
        agentPreset = null,
        updatedAt = 0L,
        pendingInteraction = null,
    )

    private fun workspace(id: String, vararg sessions: String) = WorkspaceRow(
        workspaceId = id,
        path = "/work/$id",
        title = id,
        sessionIds = sessions.toList(),
    )

    private fun ids(rows: List<SessionRow>) = rows.map { it.sessionId }

    // ---- sorting preference ------------------------------------------------------------------

    @Test
    fun `an unknown stored sort falls back to manual`() {
        assertEquals(SORT_MANUAL, normalizeSessionSort(null))
        assertEquals(SORT_MANUAL, normalizeSessionSort("alphabetical"))
        assertEquals(SORT_UPDATED, normalizeSessionSort("updated"))
        assertEquals(SORT_PROJECT, normalizeSessionSort("project"))
    }

    // ---- blocks ------------------------------------------------------------------------------

    @Test
    fun `workspaces keep the registry order and their own session order`() {
        val listable = listOf("a1", "a2", "b1", "b2").map(::session)
        val blocks = buildSessionBlocks(
            listable,
            listOf(workspace("B", "b2", "b1"), workspace("A", "a1", "a2")),
            nestedIds = emptySet(),
            ungroupedOrder = emptyList(),
        )
        assertEquals(listOf("B", "A"), blocks.map { it.key })
        assertEquals(listOf("b2", "b1", "a1", "a2"), ids(flattenBlocks(blocks)))
    }

    @Test
    fun `sessions in no workspace form one last block`() {
        val listable = listOf("a1", "loose1", "loose2").map(::session)
        val blocks = buildSessionBlocks(listable, listOf(workspace("A", "a1")), emptySet(), emptyList())
        assertEquals(listOf("A", OTHER_BLOCK_KEY), blocks.map { it.key })
        assertTrue(blocks.last().isOther)
        assertEquals(listOf("loose1", "loose2"), ids(blocks.last().roots))
    }

    @Test
    fun `the ungrouped block follows the saved order with new sessions in front`() {
        val listable = listOf("x", "y", "z", "fresh").map(::session)
        val blocks = buildSessionBlocks(listable, emptyList(), emptySet(), ungroupedOrder = listOf("z", "x", "gone", "y"))
        assertEquals(listOf("fresh", "z", "x", "y"), ids(blocks.single().roots))
    }

    @Test
    fun `a workspace with nothing visible makes no block`() {
        val listable = listOf(session("a1"))
        val blocks = buildSessionBlocks(
            listable,
            listOf(workspace("A", "a1"), workspace("empty", "archived-one")),
            emptySet(),
            emptyList(),
        )
        assertEquals(listOf("A"), blocks.map { it.key })
    }

    @Test
    fun `nested sessions are not roots and do not leave the workspace block`() {
        val listable = listOf("p", "kid", "q").map(::session)
        val blocks = buildSessionBlocks(listable, listOf(workspace("A", "p", "kid", "q")), setOf("kid"), emptyList())
        assertEquals(listOf("p", "q"), ids(blocks.single().roots))
    }

    @Test
    fun `a session listed by two workspaces is shown once, in the first`() {
        val listable = listOf("s", "t").map(::session)
        val blocks = buildSessionBlocks(listable, listOf(workspace("A", "s"), workspace("B", "s", "t")), emptySet(), emptyList())
        assertEquals(listOf("s"), ids(blocks[0].roots))
        assertEquals(listOf("t"), ids(blocks[1].roots))
    }

    // ---- live order while dragging ------------------------------------------------------------

    @Test
    fun `a live order rearranges only the dragged block`() {
        val listable = listOf("a1", "a2", "a3", "b1", "b2").map(::session)
        val blocks = buildSessionBlocks(listable, listOf(workspace("A", "a1", "a2", "a3"), workspace("B", "b1", "b2")), emptySet(), emptyList())
        val live = withLiveOrder(blocks, "A", listOf("a3", "a1", "a2"))
        assertEquals(listOf("a3", "a1", "a2"), ids(live[0].roots))
        assertSame(blocks[1], live[1])
    }

    @Test
    fun `no drag leaves the blocks alone`() {
        val blocks = buildSessionBlocks(listOf(session("a")), listOf(workspace("A", "a")), emptySet(), emptyList())
        assertSame(blocks, withLiveOrder(blocks, null, null))
        assertSame(blocks, withLiveBlockOrder(blocks, null))
    }

    @Test
    fun `a dragged project order keeps the ungrouped block last`() {
        val listable = listOf("a", "b", "loose").map(::session)
        val blocks = buildSessionBlocks(listable, listOf(workspace("A", "a"), workspace("B", "b")), emptySet(), emptyList())
        val live = withLiveBlockOrder(blocks, listOf("B", "A"))
        assertEquals(listOf("B", "A", OTHER_BLOCK_KEY), live.map { it.key })
    }

    // ---- where a lifted row lands -------------------------------------------------------------

    private val order = listOf("a", "b", "c")
    private val spans = mapOf(
        "a" to UnitSpan(0f, 100f),
        "b" to UnitSpan(100f, 200f),
        "c" to UnitSpan(200f, 300f),
    )

    @Test
    fun `a unit stays put until it crosses its neighbour's middle`() {
        assertEquals(1, nextSlotIndex(order, "b", 150f, spans))
        assertEquals(1, nextSlotIndex(order, "b", 249f, spans))
        assertEquals(1, nextSlotIndex(order, "b", 51f, spans))
    }

    @Test
    fun `crossing the middle swaps one place at a time`() {
        assertEquals(2, nextSlotIndex(order, "b", 251f, spans))
        assertEquals(0, nextSlotIndex(order, "b", 49f, spans))
        // Far past two neighbours is still one step this frame; the next frame takes the next.
        assertEquals(1, nextSlotIndex(order, "a", 290f, spans))
    }

    @Test
    fun `the first and last units cannot be dragged off the ends`() {
        assertEquals(0, nextSlotIndex(order, "a", -500f, spans))
        assertEquals(2, nextSlotIndex(order, "c", 900f, spans))
    }

    @Test
    fun `a neighbour that is not on screen is never swapped with`() {
        val partial = mapOf("a" to UnitSpan(0f, 100f), "b" to UnitSpan(100f, 200f))
        assertEquals(1, nextSlotIndex(order, "b", 900f, partial))
    }

    @Test
    fun `an unknown unit has no slot`() {
        assertEquals(-1, nextSlotIndex(order, "zzz", 0f, spans))
    }

    @Test
    fun `a tall neighbour does not make the swap flicker back`() {
        // b is 100 tall, the tall one is 300 tall. After b crosses the tall unit's middle (y 350) and
        // swaps, the tall unit moves up by b's height; b's centre must not sit back above the middle
        // of the tall unit's new span or it would swap straight back.
        val before = listOf("b", "tall")
        val spansBefore = mapOf("b" to UnitSpan(0f, 100f), "tall" to UnitSpan(100f, 400f))
        val crossing = 251f
        assertEquals(1, nextSlotIndex(before, "b", crossing, spansBefore))
        val after = listOf("tall", "b")
        val spansAfter = mapOf("tall" to UnitSpan(0f, 300f), "b" to UnitSpan(300f, 400f))
        assertEquals(1, nextSlotIndex(after, "b", crossing, spansAfter))
    }

    // ---- auto scroll --------------------------------------------------------------------------

    @Test
    fun `no scrolling in the middle of the list`() {
        assertEquals(0f, autoScrollStep(300f, 400f, 0f, 1000f, 80f, 40f), 0f)
    }

    @Test
    fun `scrolls up near the top and down near the bottom`() {
        assertTrue(autoScrollStep(20f, 120f, 0f, 1000f, 80f, 40f) < 0f)
        assertTrue(autoScrollStep(880f, 980f, 0f, 1000f, 80f, 40f) > 0f)
    }

    @Test
    fun `scrolling speeds up toward the edge and tops out`() {
        val gentle = autoScrollStep(70f, 170f, 0f, 1000f, 80f, 40f)
        val firm = autoScrollStep(10f, 110f, 0f, 1000f, 80f, 40f)
        val past = autoScrollStep(-300f, -200f, 0f, 1000f, 80f, 40f)
        assertTrue(firm < gentle)
        assertEquals(-40f, past, 0f)
    }

    @Test
    fun `a zero edge never scrolls`() {
        assertFalse(autoScrollStep(0f, 100f, 0f, 1000f, 0f, 40f) != 0f)
    }
}
