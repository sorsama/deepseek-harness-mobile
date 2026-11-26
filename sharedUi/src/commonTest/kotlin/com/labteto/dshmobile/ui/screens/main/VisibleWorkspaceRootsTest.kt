package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How much of a workspace the drawer previews before folding the rest away.
 *
 * The rule the feature lives or dies on is that the preview never lies about where you are: the
 * session you have open must stay visible in its own group even when it sorts below the cut. And
 * because the caller has already applied the user's sort choice, the slice has to come back in the
 * order it went in — a preview that reorders would silently override the sort chip.
 */
class VisibleWorkspaceRootsTest {

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

    private fun roots(vararg ids: String) = ids.map(::session)

    @Test
    fun `a short group is returned whole, in order`() {
        val rows = roots("a", "b", "c")
        assertEquals(rows, visibleWorkspaceRoots(rows, limit = 5, openPath = emptySet()))
    }

    @Test
    fun `a group exactly at the limit is returned whole`() {
        val rows = roots("a", "b", "c", "d", "e")
        assertEquals(rows, visibleWorkspaceRoots(rows, limit = 5, openPath = emptySet()))
    }

    @Test
    fun `a long group is cut to the head of the order`() {
        val rows = roots("a", "b", "c", "d", "e", "f", "g")
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            visibleWorkspaceRoots(rows, limit = 5, openPath = emptySet()).map { it.sessionId },
        )
    }

    @Test
    fun `a session below the cut on the open path is put back in its own place`() {
        val rows = roots("a", "b", "c", "d", "e", "f", "g")
        assertEquals(
            listOf("a", "b", "c", "d", "e", "f"),
            visibleWorkspaceRoots(rows, limit = 5, openPath = setOf("f")).map { it.sessionId },
        )
    }

    @Test
    fun `restoring a buried session does not lift it to the top`() {
        val rows = roots("a", "b", "c", "d", "e", "f", "g")
        val shown = visibleWorkspaceRoots(rows, limit = 3, openPath = setOf("g")).map { it.sessionId }
        assertEquals(listOf("a", "b", "c", "g"), shown)
    }

    @Test
    fun `a session already inside the cut is not duplicated`() {
        val rows = roots("a", "b", "c", "d", "e", "f")
        assertEquals(
            listOf("a", "b", "c"),
            visibleWorkspaceRoots(rows, limit = 3, openPath = setOf("b")).map { it.sessionId },
        )
    }

    @Test
    fun `every session on the open path survives the cut`() {
        val rows = roots("a", "b", "c", "d", "e", "f", "g", "h")
        assertEquals(
            listOf("a", "b", "e", "g"),
            visibleWorkspaceRoots(rows, limit = 2, openPath = setOf("e", "g")).map { it.sessionId },
        )
    }

    @Test
    fun `a non-positive limit shows everything rather than nothing`() {
        val rows = roots("a", "b", "c")
        assertEquals(rows, visibleWorkspaceRoots(rows, limit = 0, openPath = emptySet()))
    }

    @Test
    fun `an empty group stays empty`() {
        assertEquals(emptyList<SessionRow>(), visibleWorkspaceRoots(emptyList(), 5, emptySet()))
    }
}
