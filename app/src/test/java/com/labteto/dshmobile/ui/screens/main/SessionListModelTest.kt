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
    fun `no workspace payload and no saved order can make a listable session disappear`() {
        // The reporter of #70 saw "All 3" over an empty list. The count and the rows are built from
        // the same sessions, so whatever the registry and the saved order say, every listable
        // top-level session must land in exactly one block.
        val listable = listOf("a", "b", "c", "d").map(::session)
        val nested = setOf("d")
        val shapes: List<Pair<List<WorkspaceRow>, List<String>>> = listOf(
            // a 0.2.1 host with no registry rows at all, and nothing saved
            emptyList<WorkspaceRow>() to emptyList(),
            // saved order naming only sessions that are long gone
            emptyList<WorkspaceRow>() to listOf("gone-1", "gone-2"),
            // a workspace that lists ids the drawer has never heard of, and a duplicate of its own
            listOf(workspace("W", "ghost", "a", "a", "ghost-2")) to listOf("gone"),
            // an empty workspace beside one that claims everything
            listOf(workspace("E"), workspace("W", "a", "b", "c")) to emptyList(),
            // saved order that is only a subset of the ungrouped sessions
            listOf(workspace("W", "a")) to listOf("c"),
        )
        for ((workspaces, saved) in shapes) {
            val blocks = buildSessionBlocks(listable, workspaces, nested, saved)
            val shown = flattenBlocks(blocks).map { it.sessionId }
            assertEquals("each session once for $workspaces / $saved", listOf("a", "b", "c"), shown.sorted())
        }
    }

    @Test
    fun `an empty saved order is an ordering and never a filter`() {
        val listable = listOf("x", "y", "z").map(::session)
        val blocks = buildSessionBlocks(listable, emptyList(), emptySet(), emptyList())
        assertEquals(listOf("x", "y", "z"), ids(blocks.single().roots))
    }

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

    // ---- the held unit against the ends of its block (found on a device, issue #70 follow-up) ---

    /** Rows of one height, which is what a list of plain sessions is made of. */
    private fun evenSpans(order: List<String>, height: Float = 100f, top: Float = 0f): Map<String, UnitSpan> =
        order.mapIndexed { i, id -> id to UnitSpan(top + i * height, top + (i + 1) * height) }.toMap()

    @Test
    fun `a unit drawn at the end of its block never reaches the last neighbour's middle`() {
        // This is why the slot cannot be judged from the drawn position: a unit is drawn inside its
        // block, so pressed against the end its centre can only equal the last slot's middle, and a
        // swap needs the centre to pass it.
        val two = listOf("a", "b")
        val drawnTop = clampToBlock(fingerTop = 500f, height = 100f, order = two, spans = evenSpans(two))
        assertEquals(100f, drawnTop, 0f)
        assertEquals(0, nextSlotIndex(two, "a", drawnTop + 50f, evenSpans(two)))
    }

    @Test
    fun `in a block of two a row can be dragged below the other`() {
        val two = listOf("a", "b")
        assertEquals(1, slotForFinger(two, "a", fingerTop = 500f, height = 100f, spans = evenSpans(two)))
        assertEquals(0, slotForFinger(listOf("b", "a"), "a", fingerTop = -400f, height = 100f, spans = evenSpans(listOf("b", "a"))))
    }

    @Test
    fun `a row pressed past the end of a block of three takes the last place`() {
        var order = listOf("a", "b", "c")
        // One step a frame, the layout catching up in between, until the finger's place is reached.
        repeat(3) {
            val next = slotForFinger(order, "a", fingerTop = 900f, height = 100f, spans = evenSpans(order))
            order = order.toMutableList().apply { removeAt(indexOf("a")); add(next, "a") }
        }
        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun `a row pressed above the start of a block takes the first place`() {
        var order = listOf("a", "b", "c")
        repeat(3) {
            val next = slotForFinger(order, "c", fingerTop = -900f, height = 100f, spans = evenSpans(order))
            order = order.toMutableList().apply { removeAt(indexOf("c")); add(next, "c") }
        }
        assertEquals(listOf("c", "a", "b"), order)
    }

    @Test
    fun `a finger that has not passed the neighbour's middle moves nothing`() {
        val three = listOf("a", "b", "c")
        // a's top at 49 puts its centre at 99, short of b's middle at 150.
        assertEquals(0, slotForFinger(three, "a", fingerTop = 49f, height = 100f, spans = evenSpans(three)))
        // Resting where it was lifted: no neighbour is any closer than half a unit away.
        assertEquals(1, slotForFinger(three, "b", fingerTop = 100f, height = 100f, spans = evenSpans(three)))
    }

    @Test
    fun `a swap does not flicker back for rows of one height`() {
        // a is dragged until its centre is just past b's middle, and swaps. With the layout caught
        // up, the finger is still where it was and a must stay put.
        val before = listOf("a", "b")
        val finger = 101f
        assertEquals(1, slotForFinger(before, "a", finger, 100f, evenSpans(before)))
        val after = listOf("b", "a")
        assertEquals(1, slotForFinger(after, "a", finger, 100f, evenSpans(after)))
    }

    @Test
    fun `the drawn position stays inside the block`() {
        val three = listOf("a", "b", "c")
        val spans = evenSpans(three, top = 40f)
        assertEquals(140f, clampToBlock(140f, 100f, three, spans), 0f)
        assertEquals(40f, clampToBlock(-30f, 100f, three, spans), 0f)
        assertEquals(240f, clampToBlock(900f, 100f, three, spans), 0f)
    }

    @Test
    fun `a block end that is not on screen leaves that side unbounded`() {
        val three = listOf("a", "b", "c")
        val noLast = mapOf("a" to UnitSpan(0f, 100f), "b" to UnitSpan(100f, 200f))
        val (low, high) = blockDrawBounds(three, noLast, 100f)
        assertEquals(0f, low, 0f)
        assertEquals(Float.POSITIVE_INFINITY, high, 0f)
        assertEquals(900f, clampToBlock(900f, 100f, three, noLast), 0f)
    }

    @Test
    fun `a unit taller than its block is drawn where the finger is`() {
        val two = listOf("a", "b")
        // The range is empty (low above high), so there is nothing to clamp to.
        assertEquals(77f, clampToBlock(77f, 500f, two, evenSpans(two)), 0f)
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
