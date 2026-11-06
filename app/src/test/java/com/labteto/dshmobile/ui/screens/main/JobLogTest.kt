package com.labteto.dshmobile.ui.screens.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobLogTest {

    private fun log(maxChars: Int = 1_000): Pair<JobLog, MutableList<String>> {
        val lines = ArrayList<String>()
        return JobLog(lines, maxChars) to lines
    }

    @Test
    fun `whole lines become lines`() {
        val (log, lines) = log()
        log.append("one\ntwo\nthree\n")
        assertEquals(listOf("one", "two", "three"), lines)
    }

    @Test
    fun `a chunk that ends mid line is continued by the next one`() {
        val (log, lines) = log()
        log.append("buil")
        log.append("ding the pro")
        log.append("ject\nnext")
        assertEquals(listOf("building the project", "next"), lines)
    }

    @Test
    fun `a trailing newline leaves no phantom line, and the next chunk starts a fresh one`() {
        val (log, lines) = log()
        log.append("a\n")
        assertEquals(listOf("a"), lines)
        log.append("b")
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun `blank lines are kept`() {
        val (log, lines) = log()
        log.append("a\n\n\nb\n")
        assertEquals(listOf("a", "", "", "b"), lines)
    }

    @Test
    fun `an empty chunk changes nothing`() {
        val (log, lines) = log()
        log.append("")
        assertTrue(lines.isEmpty())
        assertEquals(0, log.size)
    }

    @Test
    fun `carriage returns from a CRLF host do not stay on the line`() {
        val (log, lines) = log()
        log.append("one\r\ntwo\r\n")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `a CRLF split across two chunks is still one line break`() {
        val (log, lines) = log()
        log.append("one\r")
        log.append("\ntwo")
        assertEquals(listOf("one", "two"), lines)
    }

    @Test
    fun `staying inside the budget cuts nothing`() {
        val (log, lines) = log(maxChars = 100)
        log.append("a".repeat(40) + "\n" + "b".repeat(40) + "\n")
        assertEquals(2, lines.size)
        assertFalse(log.dropped)
        assertEquals(0L, log.removedLines)
    }

    @Test
    fun `going over the budget cuts the oldest lines and says so`() {
        val (log, lines) = log(maxChars = 30)
        repeat(10) { log.append("line-$it\n") } // 7 chars + newline each
        assertTrue(log.dropped)
        assertTrue("kept ${lines.sumOf { it.length + 1 }} chars", lines.sumOf { it.length + 1 } <= 30)
        assertEquals("line-9", lines.last())
        // What is gone plus what is left is everything that was ever written.
        assertEquals(10L, log.removedLines + lines.size)
        assertEquals("line-${log.removedLines}", lines.first())
    }

    @Test
    fun `the newest line is never cut, even when it alone is over budget`() {
        val (log, lines) = log(maxChars = 10)
        log.append("old\n")
        log.append("x".repeat(25))
        assertEquals(1, lines.size)
        assertEquals(10, lines.single().length)
        assertTrue(log.dropped)
    }

    @Test
    fun `an open line that grows past the budget keeps its tail`() {
        val (log, lines) = log(maxChars = 8)
        log.append("progress 1")
        log.append("2345678")
        assertEquals(1, lines.size)
        assertEquals(8, lines.single().length)
        assertTrue(lines.single().endsWith("2345678"))
    }

    @Test
    fun `removed lines accumulate across cuts so keys stay unique`() {
        val (log, lines) = log(maxChars = 20)
        val seen = mutableSetOf<Long>()
        repeat(40) {
            log.append("n$it\n")
            // The absolute number of the newest line, as the sheet keys it.
            seen += log.removedLines + lines.lastIndex
        }
        assertEquals(40, seen.size)
    }
}
