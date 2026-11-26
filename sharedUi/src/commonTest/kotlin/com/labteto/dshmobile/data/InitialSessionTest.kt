package com.labteto.dshmobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app used to open onto an empty screen because nothing ever chose a session. These pin the
 * fallback chain that fixes it, including the exclusions that keep it from landing somewhere
 * baffling.
 */
class InitialSessionTest {

    private fun session(
        id: String,
        updatedAt: Long,
        blank: Boolean = false,
        origin: String? = null,
    ) = SessionRow(
        sessionId = id,
        title = id,
        running = false,
        blank = blank,
        parentSessionId = null,
        origin = origin,
        cwd = null,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    private fun workspace(id: String, sessions: List<String>, updatedAtEpoch: Long = 0) =
        WorkspaceRow(id, "/w/$id", id, sessions, updatedAtEpoch)

    @Test
    fun `the session you were last in wins`() {
        val sessions = listOf(session("older", 100), session("newest", 900))
        val picked = pickInitialSession(
            sessions = sessions,
            workspaces = listOf(workspace("w", listOf("older", "newest"))),
            archived = emptySet(),
            lastSessionId = "older",
        )
        assertEquals("older", picked)
    }

    @Test
    fun `a remembered session that no longer exists falls through`() {
        val sessions = listOf(session("a", 100), session("b", 900))
        val picked = pickInitialSession(sessions, listOf(workspace("w", listOf("a", "b"))), emptySet(), "gone")
        assertEquals("b", picked)
    }

    @Test
    fun `a remembered session that was archived falls through`() {
        val sessions = listOf(session("a", 100), session("b", 900))
        val picked = pickInitialSession(
            sessions = sessions,
            workspaces = listOf(workspace("w", listOf("a", "b"))),
            archived = setOf("a"),
            lastSessionId = "a",
        )
        assertEquals("b", picked)
    }

    @Test
    fun `recency comes from session activity, not workspace registration order`() {
        // The first workspace in display order holds only stale work; the second holds the session
        // the user was actually in. Manual display order must not decide this.
        val sessions = listOf(session("stale", 100), session("fresh", 900))
        val picked = pickInitialSession(
            sessions = sessions,
            workspaces = listOf(
                workspace("first", listOf("stale"), updatedAtEpoch = 5_000),
                workspace("second", listOf("fresh"), updatedAtEpoch = 1),
            ),
            archived = emptySet(),
            lastSessionId = null,
        )
        assertEquals("fresh", picked)
    }

    @Test
    fun `the workspace stamp only breaks a tie between equally recent sessions`() {
        val sessions = listOf(session("a", 500), session("b", 500))
        val picked = pickInitialSession(
            sessions = sessions,
            workspaces = listOf(
                workspace("older", listOf("a"), updatedAtEpoch = 1),
                workspace("newer", listOf("b"), updatedAtEpoch = 9),
            ),
            archived = emptySet(),
            lastSessionId = null,
        )
        assertEquals("b", picked)
    }

    @Test
    fun `subagent transcripts are never the landing session`() {
        val sessions = listOf(
            session("root", 100),
            session("child", 900, origin = "subagent"),
        )
        val picked = pickInitialSession(sessions, emptyList(), emptySet(), null)
        assertEquals("root", picked)
    }

    @Test
    fun `blank sessions lose to any started session`() {
        val sessions = listOf(session("scratch", 900, blank = true), session("real", 100))
        val picked = pickInitialSession(sessions, emptyList(), emptySet(), null)
        assertEquals("real", picked)
    }

    @Test
    fun `a blank session is still better than nothing`() {
        val sessions = listOf(session("scratch", 900, blank = true))
        val picked = pickInitialSession(sessions, emptyList(), emptySet(), null)
        assertEquals("scratch", picked)
    }

    @Test
    fun `a session outside every workspace is still eligible`() {
        val sessions = listOf(session("grouped", 100), session("loose", 900))
        val picked = pickInitialSession(
            sessions = sessions,
            workspaces = listOf(workspace("w", listOf("grouped"))),
            archived = emptySet(),
            lastSessionId = null,
        )
        assertEquals("loose", picked)
    }

    @Test
    fun `nothing to open leaves the hero on screen`() {
        assertNull(pickInitialSession(emptyList(), emptyList(), emptySet(), null))
        assertNull(
            pickInitialSession(
                sessions = listOf(session("a", 1)),
                workspaces = emptyList(),
                archived = setOf("a"),
                lastSessionId = null,
            ),
        )
    }
}
