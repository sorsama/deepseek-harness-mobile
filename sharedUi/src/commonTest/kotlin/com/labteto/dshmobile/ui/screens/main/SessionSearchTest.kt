package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.data.SessionRow
import com.labteto.dshmobile.data.WorkspaceRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drawer search.
 *
 * The behaviour under test is the half the app was missing: matching session titles and workspace
 * names locally, so that search still answers on a harness whose content index is off — which is
 * the shipped default (`session-query-sqlite` at `openAt: never`) and was reported in the field as
 * "searching does nothing".
 */
class SessionSearchTest {

    private fun session(
        id: String,
        title: String? = null,
        cwd: String? = null,
        updatedAt: Long = 0L,
        blank: Boolean = false,
    ) = SessionRow(
        sessionId = id,
        title = title,
        running = false,
        blank = blank,
        parentSessionId = null,
        origin = null,
        cwd = cwd,
        agentPreset = null,
        updatedAt = updatedAt,
        pendingInteraction = null,
    )

    private fun derive(
        sessions: List<SessionRow>,
        workspaces: List<WorkspaceRow> = emptyList(),
        archived: Set<String> = emptySet(),
        query: String,
        content: List<Pair<String, String>> = emptyList(),
        limit: Int = SEARCH_RESULT_LIMIT,
    ) = deriveSearchResults(sessions, workspaces, archived, query, content, limit)

    @Test
    fun `a title substring matches`() {
        val result = derive(listOf(session("a", title = "Fix the language changer")), query = "language")
        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `matching ignores case`() {
        val result = derive(listOf(session("a", title = "Fix The Language Changer")), query = "LANGUAGE")
        assertEquals(1, result.items.size)
    }

    @Test
    fun `a workspace name matches even when the title does not`() {
        val result = derive(
            sessions = listOf(session("a", title = "Untitled")),
            workspaces = listOf(WorkspaceRow("w", "D:/LabTeto/deepseek-mobile", "deepseek-mobile", listOf("a"))),
            query = "mobile",
        )
        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `a session outside any workspace falls back to its folder name`() {
        val result = derive(
            listOf(session("a", title = "Untitled", cwd = "D:/LabTeto/deepseek-harness")),
            query = "harness",
        )
        assertEquals(listOf("a"), result.items.map { it.session.sessionId })
        assertEquals("deepseek-harness", result.items.single().workspaceLabel)
    }

    /** A blank session's title is a localized placeholder; matching it would tie results to a language. */
    @Test
    fun `blank sessions never match`() {
        val result = derive(listOf(session("a", title = "Planning work", blank = true)), query = "planning")
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `archived sessions never match`() {
        val result = derive(
            sessions = listOf(session("a", title = "Planning work")),
            archived = setOf("a"),
            query = "planning",
        )
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        val result = derive(listOf(session("a", title = "Planning work")), query = "   ")
        assertTrue(result.items.isEmpty())
        assertFalse(result.hasMore)
    }

    @Test
    fun `local rows are ordered newest first`() {
        val result = derive(
            listOf(
                session("old", title = "plan one", updatedAt = 100),
                session("new", title = "plan two", updatedAt = 900),
            ),
            query = "plan",
        )
        assertEquals(listOf("new", "old"), result.items.map { it.session.sessionId })
    }

    /** Content search is an extra, so its hits go after everything the title filter already found. */
    @Test
    fun `content-only hits follow local matches in host order`() {
        val result = derive(
            sessions = listOf(
                session("local", title = "plan the migration", updatedAt = 10),
                session("remote", title = "unrelated", updatedAt = 999),
            ),
            query = "plan",
            content = listOf("remote" to "…we should plan this…"),
        )
        assertEquals(listOf("local", "remote"), result.items.map { it.session.sessionId })
    }

    @Test
    fun `a session matched both ways appears once and keeps the snippet`() {
        val result = derive(
            sessions = listOf(session("a", title = "plan the migration")),
            query = "plan",
            content = listOf("a" to "…we should plan this…"),
        )
        assertEquals(1, result.items.size)
        assertEquals("…we should plan this…", result.items.single().snippet)
    }

    @Test
    fun `a purely local match carries no snippet`() {
        val result = derive(listOf(session("a", title = "plan the migration")), query = "plan")
        assertNull(result.items.single().snippet)
    }

    @Test
    fun `a content hit for an archived session is dropped`() {
        val result = derive(
            sessions = listOf(session("a", title = "unrelated")),
            archived = setOf("a"),
            query = "plan",
            content = listOf("a" to "…plan…"),
        )
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `results are capped and report that there are more`() {
        val sessions = (1..10).map { session("s$it", title = "plan $it", updatedAt = it.toLong()) }
        val result = derive(sessions, query = "plan", limit = 4)
        assertEquals(4, result.items.size)
        assertTrue(result.hasMore)
    }

    @Test
    fun `an exactly-full page does not claim more`() {
        val sessions = (1..4).map { session("s$it", title = "plan $it", updatedAt = it.toLong()) }
        val result = derive(sessions, query = "plan", limit = 4)
        assertEquals(4, result.items.size)
        assertFalse(result.hasMore)
    }
}
