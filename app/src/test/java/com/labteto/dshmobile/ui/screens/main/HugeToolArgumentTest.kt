package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A model can send a tool call with hundreds of kilobytes of arguments (a 300 KB command made the
 * built-in runtime refuse to ask, and the app's native heap passed 3 GB laying the row out). What
 * the chat lays out is cut; the call itself keeps every byte.
 */
class HugeToolArgumentTest {
    private val huge = "echo huge-ran # " + "x".repeat(300 * 1024)

    @Test
    fun `a row summary is cut to one short line`() {
        val row = toolRowModel("bash", """{"command":"$huge"}""", null)
        val summary = row.summary!!
        assertTrue(summary.length <= ROW_SUMMARY_MAX_CHARS + 1)
        assertTrue(summary.startsWith("echo huge-ran # xxx"))
        assertTrue(summary.endsWith("…"))
    }

    @Test
    fun `a multi-line command shows its first line and says it goes on`() {
        assertEquals("mkdir -p out…", rowSummaryText("mkdir -p out\ncd out\nls"))
        assertEquals("ls -la", rowSummaryText("ls -la"))
    }

    @Test
    fun `a terminal card lays out at most the cap`() {
        val shown = cardCommandText(huge)
        assertEquals(CARD_COMMAND_MAX_CHARS + 2, shown.length)
        assertEquals("uname -a", cardCommandText("uname -a"))
    }
}
