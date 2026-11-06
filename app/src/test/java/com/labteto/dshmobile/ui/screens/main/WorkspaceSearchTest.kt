package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the file explorer's search keeps — in the folder listing and in the "Changed this session" card. */
class WorkspaceSearchTest {

    @Test
    fun `a blank query keeps everything`() {
        assertTrue(matchesFileQuery("README.md", ""))
        assertTrue(matchesFileQuery("README.md", "   "))
    }

    @Test
    fun `matching ignores case and the padding around the query`() {
        assertTrue(matchesFileQuery("README.md", "readme"))
        assertTrue(matchesFileQuery("README.md", "  MD "))
        assertFalse(matchesFileQuery("README.md", "license"))
    }

    @Test
    fun `the card matches on the whole path`() {
        val changed = listOf("server.js", "src/todos.js", "test/pagination.test.js")

        assertEquals(listOf("src/todos.js"), filterChangedFiles(changed, "todos"))
        // A folder name is part of the path, so it finds the files inside it.
        assertEquals(listOf("test/pagination.test.js"), filterChangedFiles(changed, "test/"))
    }

    @Test
    fun `the card keeps its order and every entry for a blank query`() {
        val changed = listOf("b.kt", "a.kt")

        assertEquals(changed, filterChangedFiles(changed, ""))
        assertEquals(listOf("b.kt", "a.kt"), filterChangedFiles(changed, ".KT"))
    }

    @Test
    fun `a query that matches nothing empties the card`() {
        assertTrue(filterChangedFiles(listOf("a.kt", "b.kt"), "zzz").isEmpty())
    }
}
