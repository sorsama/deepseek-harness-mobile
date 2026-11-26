package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the drawer decides what nests under what.
 *
 * The distinction that carries the whole feature is `origin`: `parentSessionId` alone does not mean
 * "subagent", because an ordinary fork sets one too — and a fork is a session in its own right that
 * belongs at the top level, not tucked inside the session it branched from.
 */
class SubagentTreeTest {

    private fun session(
        id: String,
        parent: String? = null,
        origin: String? = null,
    ) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = false,
        parentSessionId = parent,
        origin = origin,
        cwd = null,
        agentPreset = null,
        updatedAt = 0L,
        pendingInteraction = null,
    )

    private fun index(listable: List<SessionRow>, all: List<SessionRow> = listable) =
        indexSubagents(listable, all.associateBy { it.sessionId })

    @Test
    fun `a subagent nests under its parent`() {
        val rows = listOf(session("root"), session("kid", parent = "root", origin = "subagent"))
        assertEquals(mapOf("root" to listOf(rows[1])), index(rows))
    }

    @Test
    fun `a fork stays at the top level`() {
        val rows = listOf(session("root"), session("forked", parent = "root"))
        assertTrue(index(rows).isEmpty())
    }

    @Test
    fun `a session with no parent nests nowhere`() {
        assertTrue(index(listOf(session("root"), session("other"))).isEmpty())
    }

    @Test
    fun `subagents of a subagent nest under it, not under the root`() {
        val rows = listOf(
            session("root"),
            session("kid", parent = "root", origin = "subagent"),
            session("grandkid", parent = "kid", origin = "subagent"),
        )
        val tree = index(rows)
        assertEquals(listOf("kid"), tree["root"]?.map { it.sessionId })
        assertEquals(listOf("grandkid"), tree["kid"]?.map { it.sessionId })
    }

    /** Archiving a parent used to take its subagents off the list with it. */
    @Test
    fun `a subagent whose parent is hidden re-attaches to the nearest visible ancestor`() {
        val root = session("root")
        val hidden = session("hidden", parent = "root", origin = "subagent")
        val orphan = session("orphan", parent = "hidden", origin = "subagent")
        val tree = index(listable = listOf(root, orphan), all = listOf(root, hidden, orphan))
        assertEquals(listOf("orphan"), tree["root"]?.map { it.sessionId })
    }

    @Test
    fun `a subagent with no visible ancestor is left ungrouped`() {
        val hidden = session("hidden", origin = "subagent")
        val orphan = session("orphan", parent = "hidden", origin = "subagent")
        assertTrue(index(listable = listOf(orphan), all = listOf(hidden, orphan)).isEmpty())
    }

    /** A malformed lineage must not spin the walk forever — the drawer would hang with it. */
    @Test
    fun `a lineage cycle terminates`() {
        val a = session("a", parent = "b", origin = "subagent")
        val b = session("b", parent = "a", origin = "subagent")
        val tree = index(listable = listOf(a), all = listOf(a, b))
        assertTrue(tree.isEmpty())
    }

    @Test
    fun `several subagents of one parent are grouped together`() {
        val rows = listOf(
            session("root"),
            session("k1", parent = "root", origin = "subagent"),
            session("k2", parent = "root", origin = "subagent"),
        )
        assertEquals(listOf("k1", "k2"), index(rows)["root"]?.map { it.sessionId })
    }
}
