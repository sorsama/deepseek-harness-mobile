package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.connection.AppSettings
import com.labteto.dshmobile.connection.DEFAULT_WORK_STEP_DISPLAY
import com.labteto.dshmobile.connection.WorkStepDisplay
import com.labteto.dshmobile.core.session.*
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DiffHunk
import com.labteto.dshmobile.ui.components.ReadLine
import com.labteto.dshmobile.ui.components.SearchFile
import com.labteto.dshmobile.ui.components.SearchMatch
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.ToolCardView
import org.junit.Assert.*
import org.junit.Test

class WorkStepPresentationTest {
    private fun call(seq: Long, name: String = "bash") = ToolCallNode(seq, "c$seq", name, "{\"command\":\"ls\"}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun answer(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))

    // ---- the mapping from level to presentation ----------------------------------------------

    @Test fun `compact folds turns and groups every call without live detail or previews`() {
        val p = workStepPolicyFor(WorkStepDisplay.Compact)
        assertTrue(p.foldCompletedTurns)
        assertEquals(StepGrouping.Collapsed, p.stepGrouping)
        assertFalse(p.liveProcessDetail)
        assertFalse(p.settledReasoningPreview)
        assertFalse(p.rowsStartOpen)
    }

    @Test fun `standard adds live detail and the reasoning preview`() {
        val p = workStepPolicyFor(WorkStepDisplay.Standard)
        assertTrue(p.foldCompletedTurns)
        assertEquals(StepGrouping.Collapsed, p.stepGrouping)
        assertTrue(p.liveProcessDetail)
        assertTrue(p.settledReasoningPreview)
        assertFalse(p.rowsStartOpen)
    }

    @Test fun `detailed groups only turns that are over`() {
        val p = workStepPolicyFor(WorkStepDisplay.Detailed)
        assertTrue(p.foldCompletedTurns)
        assertEquals(StepGrouping.History, p.stepGrouping)
        assertTrue(p.liveProcessDetail)
        assertTrue(p.settledReasoningPreview)
        assertFalse(p.rowsStartOpen)
    }

    @Test fun `fully expanded folds nothing and opens rows`() {
        val p = workStepPolicyFor(WorkStepDisplay.Verbose)
        assertFalse(p.foldCompletedTurns)
        assertEquals(StepGrouping.None, p.stepGrouping)
        assertFalse(p.liveProcessDetail)
        assertTrue(p.settledReasoningPreview)
        assertTrue(p.rowsStartOpen)
    }

    @Test fun `every level has its own policy and the same level gives the same instance`() {
        val policies = WorkStepDisplay.entries.map { workStepPolicyFor(it) }
        assertEquals(4, policies.toSet().size)
        WorkStepDisplay.entries.forEach {
            assertSame(workStepPolicyFor(it), workStepPolicyFor(it))
            assertEquals(it, workStepPolicyFor(it).mode)
        }
    }

    @Test fun `the default level is the compact look and a fresh install uses it`() {
        assertEquals(WorkStepDisplay.Compact, DEFAULT_WORK_STEP_DISPLAY)
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of(AppSettings().workStepDisplay))
        assertEquals(workStepPolicyFor(WorkStepDisplay.Compact), WorkStepPresentation.Default.policy)
    }

    @Test fun `a stored value that is not a level reads as the default`() {
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of(null))
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of(""))
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of("Verbose "))
        // The web client's legacy names are host settings; this device never reads them.
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of("normal"))
        assertEquals(WorkStepDisplay.Compact, WorkStepDisplay.of("expanded"))
        WorkStepDisplay.entries.forEach { assertEquals(it, WorkStepDisplay.of(it.key)) }
    }

    @Test fun `only the fully expanded level starts rows open`() {
        WorkStepDisplay.entries.forEach { level ->
            val default = WorkStepPresentation(workStepPolicyFor(level)).defaultFor(5)
            assertEquals(level == WorkStepDisplay.Verbose, default == RowDisclosure.Preview)
            assertEquals(level != WorkStepDisplay.Verbose, default == RowDisclosure.Closed)
        }
    }

    @Test fun `a list that is not lazy opens only rows from its floor`() {
        val p = WorkStepPresentation(workStepPolicyFor(WorkStepDisplay.Verbose), openFromSeq = 10)
        assertEquals(RowDisclosure.Closed, p.defaultFor(9))
        assertEquals(RowDisclosure.Preview, p.defaultFor(10))
    }

    // ---- taps are temporary and never reach the setting --------------------------------------

    @Test fun `a tap on an open row closes only that row`() {
        val taps = RowDisclosureMemory()
        taps.toggle("tool:a", RowDisclosure.Preview)
        assertEquals(RowDisclosure.Closed, taps.resolve("tool:a", RowDisclosure.Preview))
        assertEquals(RowDisclosure.Preview, taps.resolve("tool:b", RowDisclosure.Preview))
    }

    @Test fun `a tap on a closed row opens it in full and a second tap closes it`() {
        val taps = RowDisclosureMemory()
        taps.toggle("tool:a", RowDisclosure.Closed)
        assertEquals(RowDisclosure.Full, taps.resolve("tool:a", RowDisclosure.Closed))
        taps.toggle("tool:a", RowDisclosure.Closed)
        assertEquals(RowDisclosure.Closed, taps.resolve("tool:a", RowDisclosure.Closed))
    }

    @Test fun `show all opens a previewed row in full`() {
        val taps = RowDisclosureMemory()
        taps.showAll("tool:a")
        assertEquals(RowDisclosure.Full, taps.resolve("tool:a", RowDisclosure.Preview))
    }

    @Test fun `tapping rows never changes the stored level or the policy`() {
        val settings = AppSettings(workStepDisplay = WorkStepDisplay.Verbose.key)
        val policy = workStepPolicyFor(WorkStepDisplay.of(settings.workStepDisplay))
        val taps = RowDisclosureMemory()
        repeat(3) { taps.toggle("tool:$it", RowDisclosure.Preview) }
        taps.showAll("tool:9")
        assertEquals(4, taps.tapCount)
        assertEquals(WorkStepDisplay.Verbose.key, settings.workStepDisplay)
        assertSame(policy, workStepPolicyFor(WorkStepDisplay.of(settings.workStepDisplay)))
    }

    @Test fun `a new level starts from a fresh memory so every row follows it again`() {
        val before = RowDisclosureMemory()
        before.toggle("tool:a", RowDisclosure.Preview)
        // The screen keys the memory on the level, so a changed level means a new memory.
        val after = RowDisclosureMemory()
        assertEquals(0, after.tapCount)
        assertEquals(RowDisclosure.Preview, after.resolve("tool:a", RowDisclosure.Preview))
        assertEquals(RowDisclosure.Closed, before.resolve("tool:a", RowDisclosure.Preview))
    }

    // ---- step grouping -----------------------------------------------------------------------

    private fun rows(running: Boolean = false) = groupToolRows(
        listOf(user(2), call(3), call(4), answer(5), user(7), call(8), call(9)),
    )

    private val turnStarts = listOf(1L, 6L)

    @Test fun `collapsed keeps every group`() {
        val out = applyStepGrouping(rows(), StepGrouping.Collapsed, turnStarts, running = true)
        assertEquals(rows(), out)
    }

    @Test fun `none shows every call as its own ungrouped row`() {
        val out = applyStepGrouping(rows(), StepGrouping.None, turnStarts, running = false)
        val tools = out.filterIsInstance<TranscriptRow.Tools>()
        assertEquals(listOf(3L, 4L, 8L, 9L), tools.map { it.seq })
        assertTrue(tools.all { !it.grouped && it.calls.size == 1 })
    }

    @Test fun `history ungroups only the running turn`() {
        val out = applyStepGrouping(rows(), StepGrouping.History, turnStarts, running = true)
        val tools = out.filterIsInstance<TranscriptRow.Tools>()
        assertEquals(listOf(listOf(3L, 4L)), tools.filter { it.grouped }.map { row -> row.calls.map { it.seq } })
        assertEquals(listOf(8L, 9L), tools.filter { !it.grouped }.map { it.seq })
    }

    @Test fun `history keeps every group once nothing is running`() {
        val out = applyStepGrouping(rows(), StepGrouping.History, turnStarts, running = false)
        assertTrue(out.filterIsInstance<TranscriptRow.Tools>().all { it.grouped })
    }

    @Test fun `ungrouped rows keep distinct stable list keys`() {
        val out = applyStepGrouping(rows(), StepGrouping.None, turnStarts, running = false)
        assertEquals(out.map { it.seq }.size, out.map { it.seq }.toSet().size)
    }

    @Test fun `a fully expanded session folds into no process row`() {
        // The policy gates the fold: with it off the transcript keeps every step on screen.
        val thinking = AssistantMessageNode(3, "r3", 1, 1, listOf(ChatBlock("reasoning", "hmm")))
        val nodes = listOf(user(2), thinking, call(4), answer(5))
        val grouped = applyStepGrouping(groupToolRows(nodes), StepGrouping.None, listOf(1L), running = false)
        assertTrue(grouped.none { it is TranscriptRow.Process })
        val folded = foldProcessRows(groupToolRows(nodes), listOf(1L), false)
        assertTrue(folded.any { it is TranscriptRow.Process })
    }

    // ---- a running group's header -----------------------------------------------------------

    @Test fun `a running group names the newest call without a result`() {
        val calls = listOf(call(3), call(4, "read").copy(arguments = "{\"file_path\":\"a.txt\"}"))
        assertEquals("Read · a.txt", liveGroupDetail(calls, setOf("c3"), cwd = null))
        assertEquals("Read · a.txt", liveGroupDetail(calls, emptySet(), cwd = null))
        assertEquals("Bash · ls", liveGroupDetail(calls.take(1), emptySet(), cwd = null))
    }

    @Test fun `a settled group has no live detail`() {
        assertNull(liveGroupDetail(listOf(call(3)), setOf("c3"), cwd = null))
    }

    // ---- bounds on a level-opened row ------------------------------------------------------

    @Test fun `short text is not clipped`() {
        assertEquals("one\ntwo", clipPreview("one\ntwo"))
        assertFalse(exceedsPreview("one\ntwo"))
        assertEquals("", clipPreview(""))
    }

    @Test fun `text is cut at the character bound`() {
        val text = "x".repeat(PREVIEW_MAX_CHARS * 50)
        val out = clipPreview(text)
        assertTrue(out.length <= PREVIEW_MAX_CHARS + 2)
        assertTrue(out.endsWith("…"))
        assertTrue(exceedsPreview(text))
    }

    @Test fun `text is cut at the line bound`() {
        val text = (1..PREVIEW_MAX_LINES * 3).joinToString("\n") { "line $it" }
        val out = clipPreview(text)
        assertEquals(PREVIEW_MAX_LINES, out.removeSuffix("\n…").lines().size)
        assertTrue(out.startsWith("line 1\nline 2"))
    }

    @Test fun `a megabyte of text is cut without being laid out`() {
        val out = clipPreview("a".repeat(5_000_000))
        assertTrue(out.length < 5_000)
    }

    @Test fun `the reasoning preview is the first non blank line`() {
        assertEquals("Plan the change", firstLinePreview("\n\n  **Plan** the change  \nthen more"))
        assertNull(firstLinePreview("   \n \n"))
        assertNull(firstLinePreview(""))
        assertEquals(161, firstLinePreview("y".repeat(10_000))!!.length)
    }

    @Test fun `a terminal card keeps the start of its output`() {
        val card = ToolCardView.TerminalCard(output = "o".repeat(1_000_000), exitCode = 0)
        val clipped = clipCardForPreview(card)
        assertTrue(clipped.clipped)
        assertTrue((clipped.view as ToolCardView.TerminalCard).output!!.length < 5_000)
        assertEquals(0, (clipped.view as ToolCardView.TerminalCard).exitCode)
    }

    @Test fun `a card that fits is returned as it was`() {
        val card = ToolCardView.TerminalCard(output = "ok", exitCode = 0)
        val clipped = clipCardForPreview(card)
        assertFalse(clipped.clipped)
        assertEquals(card, clipped.view)
    }

    @Test fun `a read card shows the first lines only`() {
        val lines = (1..500).map { ReadLine(it, "text $it") }
        val clipped = clipCardForPreview(ToolCardView.ReadCard(label = "f", lines = lines, totalLines = 500))
        assertTrue(clipped.clipped)
        val view = clipped.view as ToolCardView.ReadCard
        assertEquals(PREVIEW_MAX_LINES, view.lines.size)
        assertEquals(500, view.totalLines)
    }

    @Test fun `a diff card clips its hunks and their count`() {
        val hunks = (1..20).map { DiffHunk("f$it", null, "n".repeat(100_000)) }
        val clipped = clipCardForPreview(ToolCardView.DiffCard(diffs = hunks))
        val view = clipped.view as ToolCardView.DiffCard
        assertTrue(clipped.clipped)
        assertEquals(PREVIEW_MAX_ITEMS, view.diffs.size)
        assertTrue(view.diffs.all { it.newText!!.length < 5_000 })
    }

    @Test fun `search hits are limited across files`() {
        val files = (1..5).map { f -> SearchFile("f$f", (1..30).map { SearchMatch(it, "m") }) }
        val clipped = clipCardForPreview(ToolCardView.SearchCard(matches = SearchMatches.FileMatches(files)))
        val shown = (clipped.view as ToolCardView.SearchCard).matches as SearchMatches.FileMatches
        assertTrue(clipped.clipped)
        assertEquals(PREVIEW_MAX_LINES, shown.files.sumOf { it.matches.size })
    }

    @Test fun `a generic card clips its input and its blocks`() {
        val card = ToolCardView.GenericCard(
            title = "t",
            rawInput = "{\"a\":\"" + "z".repeat(300_000) + "\"}",
            content = (1..20).map { ContentBlockView.TextBlock("b".repeat(50_000)) },
        )
        val clipped = clipCardForPreview(card)
        val view = clipped.view as ToolCardView.GenericCard
        assertTrue(clipped.clipped)
        assertTrue(view.rawInput!!.length < 5_000)
        assertEquals(PREVIEW_MAX_ITEMS, view.content!!.size)
        assertTrue(view.content!!.all { (it as ContentBlockView.TextBlock).text.length < 5_000 })
    }
}
