package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.ui.components.CodeWrapMemory
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DiffHunk
import com.labteto.dshmobile.ui.components.DiffTotals
import com.labteto.dshmobile.ui.components.LineIndex
import com.labteto.dshmobile.ui.components.ReadLine
import com.labteto.dshmobile.ui.components.SearchFile
import com.labteto.dshmobile.ui.components.SearchMatch
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.codeWrapKey
import com.labteto.dshmobile.ui.components.hasLongLine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output rules of the chat's tool cards: inline blocks are always bounded however large the
 * output, "Show all" can rebuild the whole text from the unclipped card, and the wrap choice of a
 * block is remembered under a stable key.
 */
class ChatOutputPolishTest {

    private val fiveMegabytes = "0123456789abcdef".repeat(327_680) // 5,242,880 chars

    private fun lines(count: Int) = (1..count).joinToString("\n") { "build line $it" }

    // ---- Inline bounds --------------------------------------------------------------------

    @Test
    fun `a 5 MB output is cut to a few lines before it is laid out`() {
        val shown = clipPreview(fiveMegabytes)
        assertTrue(shown.length <= PREVIEW_MAX_CHARS + 2)
        assertTrue(shown.lines().size <= PREVIEW_MAX_LINES + 1)
    }

    @Test
    fun `a 200000 line terminal card keeps only the inline bound and says it was cut`() {
        val card = ToolCardView.TerminalCard(title = "make", output = lines(200_000), exitCode = 0)
        val clipped = clipCardForPreview(card)
        assertTrue(clipped.clipped)
        val output = (clipped.view as ToolCardView.TerminalCard).output!!
        assertEquals(PREVIEW_MAX_LINES, output.removeSuffix("\n…").lines().size)
    }

    @Test
    fun `an inline block is never taller than the bound whatever the output`() {
        // The transcript does not scroll a block inside itself: its height is its line count, which
        // is why the bound is a line count. 24 lines of code is about a screen.
        assertTrue(PREVIEW_MAX_LINES <= 24)
        val read = ToolCardView.ReadCard(label = "a.kt", lines = (1..100_000).map { ReadLine(it, "val x$it = $it") }, totalLines = 100_000)
        assertEquals(PREVIEW_MAX_LINES, (clipCardForPreview(read).view as ToolCardView.ReadCard).lines.size)
    }

    @Test
    fun `a cut diff still counts the whole change`() {
        val big = (1..5_000).joinToString("\n") { "added $it" }
        val diff = ToolCardView.DiffCard(diffs = listOf(DiffHunk("big.txt", null, big)))
        val clipped = clipCardForPreview(diff)
        assertTrue(clipped.clipped)
        val view = clipped.view as ToolCardView.DiffCard
        assertEquals(DiffTotals(added = 5_000, removed = 0, files = 1), view.totals)
        // A change that fits is not cut and carries no totals of its own.
        val small = clipCardForPreview(ToolCardView.DiffCard(diffs = listOf(DiffHunk("a.txt", "x", "y"))))
        assertFalse(small.clipped)
        assertNull((small.view as ToolCardView.DiffCard).totals)
    }

    @Test
    fun `a result of megabytes is recognised as huge without being copied`() {
        val huge = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", fiveMegabytes) }))
        assertTrue(isHugeResult(huge))
        val small = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "ok") }))
        assertFalse(isHugeResult(small))
        assertTrue(isHugeResult(JsonPrimitive(fiveMegabytes)))
        // Counted across blocks too.
        val many = JsonArray(List(30) { buildJsonObject { put("type", "text"); put("text", "x".repeat(10_000)) } })
        assertTrue(isHugeResult(many))
    }

    // ---- Show all ---------------------------------------------------------------------------

    @Test
    fun `show all rebuilds the whole output from the unclipped card`() {
        val full = lines(200_000)
        val card = ToolCardView.TerminalCard(title = "make", output = full, exitCode = 0)
        val text = cardPlainText(card)
        assertTrue(text.startsWith("$ make\n"))
        assertTrue(text.endsWith("build line 200000"))
        // The viewer indexes it line by line: one display line per source line, plus the command.
        assertEquals(200_001, LineIndex.of(text).count)
    }

    @Test
    fun `show all of a 5 MB single line output stays bounded per display line`() {
        val text = cardPlainText(ToolCardView.TerminalCard(title = "cat blob", output = fiveMegabytes, exitCode = 0))
        val index = LineIndex.of(text)
        assertTrue(index.count > 2_000)
        assertTrue((0 until index.count step 101).all { index.line(it).length <= LineIndex.DEFAULT_MAX_LINE_CHARS })
    }

    @Test
    fun `show all covers every kind of card`() {
        val diff = cardPlainText(ToolCardView.DiffCard(diffs = listOf(DiffHunk("a.txt", "old line", "new line"))))
        assertEquals("--- a.txt\n-old line\n+new line\n", diff)

        val read = cardPlainText(ToolCardView.ReadCard(label = "a.kt", lines = listOf(ReadLine(7, "fun a()"), ReadLine(8, "}")), totalLines = 9))
        assertEquals("7\tfun a()\n8\t}\n", read)

        val files = cardPlainText(ToolCardView.SearchCard(matches = SearchMatches.FileMatches(listOf(SearchFile("src/a.kt", listOf(SearchMatch(3, "TODO")))))))
        assertEquals("src/a.kt\n  3: TODO\n", files)
        assertEquals("a\nb\n", cardPlainText(ToolCardView.SearchCard(matches = SearchMatches.PathList(listOf("a", "b")))))

        val generic = cardPlainText(
            ToolCardView.GenericCard(title = "x", rawInput = "{\"a\":1}", content = listOf(ContentBlockView.TextBlock("result text"))),
        )
        assertEquals("{\"a\":1}\n\nresult text\n", generic)
        // A generic card that takes no arguments has no arguments text.
        assertEquals("only output\n", cardPlainText(ToolCardView.GenericCard(title = "x", rawInput = "{}", content = listOf(ContentBlockView.TextBlock("only output")))))
    }

    @Test
    fun `a very long command is bounded inline and whole in show all`() {
        val command = "echo " + "x".repeat(50_000)
        val card = ToolCardView.TerminalCard(title = command, output = "done", exitCode = 0)
        val clipped = clipCardForPreview(card)
        assertTrue(clipped.clipped)
        assertTrue((clipped.view as ToolCardView.TerminalCard).title!!.length <= PREVIEW_MAX_CHARS + 2)
        assertTrue(cardPlainText(card).startsWith("$ $command\n"))
    }

    // ---- Names ------------------------------------------------------------------------------

    @Test
    fun `a job's kind and a PowerShell call read as the shell's name everywhere`() {
        assertEquals("PowerShell", jobKindLabel("pwsh"))
        assertEquals("PowerShell", jobKindLabel("PWSH"))
        assertEquals("Bash", jobKindLabel("bash"))
        assertEquals("Pty send", jobKindLabel("pty-send"))
        assertEquals("Subagent", jobKindLabel("subagent"))
        // The same name the row title and the group header use.
        assertEquals("PowerShell", toolRowModel("pwsh", """{"command":"ls","description":"List"}""", cwd = null).title)
    }

    @Test
    fun `an event type this build has no words for reads as words`() {
        assertEquals("Tool ptc dispatch", humanizeIdentifier("tool/ptc-dispatch"))
        assertEquals("Plugin acme sync run", humanizeIdentifier("plugin:acme/sync-run"))
    }

    // ---- Wrapping ---------------------------------------------------------------------------

    @Test
    fun `the wrap switch is offered only when a line is too long for a phone`() {
        assertFalse(hasLongLine("short\nlines\nonly"))
        assertTrue(hasLongLine("a".repeat(37)))
        assertTrue(hasLongLine("ok\n" + "b".repeat(100)))
        assertFalse(hasLongLine(""))
        assertTrue(hasLongLine("x".repeat(30), threshold = 28))
        // A long total with short lines is not a long line.
        assertFalse(hasLongLine((1..1_000).joinToString("\n") { "line $it" }))
    }

    @Test
    fun `a block remembers its wrap choice under its call and its own name`() {
        val memory = CodeWrapMemory()
        val terminal = codeWrapKey("call-1", "out")
        val read = codeWrapKey("call-1", "read")
        assertFalse(memory.isWrapped(terminal))
        memory.toggle(terminal)
        assertTrue(memory.isWrapped(terminal))
        // Another block of the same call, and the same block of another call, are their own.
        assertFalse(memory.isWrapped(read))
        assertFalse(memory.isWrapped(codeWrapKey("call-2", "out")))
        memory.toggle(terminal)
        assertFalse(memory.isWrapped(terminal))
        assertNotNull(CodeWrapMemory.Shared)
    }
}
