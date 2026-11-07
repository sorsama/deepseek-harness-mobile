package com.labteto.dshmobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun parsesGfmTableWithAlignments() {
        val blocks = parseMarkdown(
            """
            | Name  | Age | Score |
            |:------|:---:|------:|
            | Ada   | 36  | 99.5  |
            | Alan  | 41  | 87.0  |
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("Name", "Age", "Score"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Ada", "36", "99.5"), table.rows[0])
        assertEquals(
            listOf(MdBlock.Alignment.LEFT, MdBlock.Alignment.CENTER, MdBlock.Alignment.RIGHT),
            table.alignments,
        )
    }

    @Test
    fun plainPipeTableDefaultsToLeftAlignment() {
        val blocks = parseMarkdown(
            """
            | a | b |
            | - | - |
            | 1 | 2 |
            """.trimIndent(),
        )
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf(MdBlock.Alignment.LEFT, MdBlock.Alignment.LEFT), table.alignments)
    }

    @Test
    fun pipeLineWithoutSeparatorIsNotATable() {
        val blocks = parseMarkdown("| just a line with pipes | and more |")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun shortRowDoesNotCrashAndMissingCellsAreEmpty() {
        val blocks = parseMarkdown(
            """
            | a | b | c |
            | - | - | - |
            | 1 |
            """.trimIndent(),
        )
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("1", "", ""), table.rows[0])
    }

    @Test
    fun nestedListItemsCarryIndentDepth() {
        val blocks = parseMarkdown(
            """
            - top one
              - child one
            - top two
            """.trimIndent(),
        )
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(3, items.size)
        assertEquals("top one", items[0].text)
        assertEquals(0, items[0].indent)
        assertEquals("child one", items[1].text)
        assertEquals(1, items[1].indent)
        assertEquals("top two", items[2].text)
    }

    @Test
    fun orderedListStripsMarker() {
        val blocks = parseMarkdown("1. first\n2. second\n3. third")
        val items = blocks.filterIsInstance<MdBlock.ListItem>()
        assertEquals(listOf("first", "second", "third"), items.map { it.text })
    }

    @Test
    fun horizontalRuleIsRecognized() {
        val blocks = parseMarkdown("before\n\n---\n\nafter")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1] is MdBlock.HorizontalRule)
    }

    @Test
    fun fencedCodeKeepsPipesAndHeadingsVerbatim() {
        val blocks = parseMarkdown(
            """
            ```text
            | not | a | table |
            # not a heading
            ```
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertTrue(code.code.contains("| not | a | table |"))
        assertTrue(code.code.contains("# not a heading"))
    }

    @Test
    fun inlineSegmentsParseBoldItalicCodeLinkAndStrike() {
        val segments = parseInlineSegments("a **b** *c* `d` ~~e~~ [f](https://x.y)")
        assertEquals(
            listOf(
                InlineSegment.Plain("a "),
                InlineSegment.Bold("b"),
                InlineSegment.Plain(" "),
                InlineSegment.Italic("c"),
                InlineSegment.Plain(" "),
                InlineSegment.Code("d"),
                InlineSegment.Plain(" "),
                InlineSegment.Strikethrough("e"),
                InlineSegment.Plain(" "),
                InlineSegment.Link("f", "https://x.y"),
            ),
            segments,
        )
    }

    @Test
    fun unmatchedCodeBacktickStaysLiteral() {
        val segments = parseInlineSegments("a ` b")
        assertEquals(listOf(InlineSegment.Plain("a ` b")), segments)
    }

    @Test
    fun boldRequiresNonEmptyContent() {
        val segments = parseInlineSegments("x ** y")
        assertEquals(listOf(InlineSegment.Plain("x ** y")), segments)
    }

    @Test
    fun headingLevelAndText() {
        val blocks = parseMarkdown("### Title with **bold** ###")
        val heading = blocks[0] as MdBlock.Heading
        assertEquals(3, heading.level)
        assertEquals("Title with **bold**", heading.text)
    }

    @Test
    fun blockquoteCollectsConsecutiveLines() {
        val blocks = parseMarkdown("> one\n> two\n\npara")
        val quote = blocks[0] as MdBlock.Blockquote
        assertEquals(listOf("one", "two"), quote.lines)
    }

    // ---- Task lists -----------------------------------------------------------

    @Test
    fun taskListItemChecked() {
        val blocks = parseMarkdown("- [x] done thing")
        val item = blocks[0] as MdBlock.ListItem
        assertEquals("done thing", item.text)
        assertEquals(true, item.checked)
    }

    @Test
    fun taskListItemUnchecked() {
        val blocks = parseMarkdown("- [ ] open thing")
        val item = blocks[0] as MdBlock.ListItem
        assertEquals("open thing", item.text)
        assertEquals(false, item.checked)
    }

    @Test
    fun taskListItemUpperCaseX() {
        val blocks = parseMarkdown("* [X] also done")
        val item = blocks[0] as MdBlock.ListItem
        assertEquals("also done", item.text)
        assertEquals(true, item.checked)
    }

    @Test
    fun orderedTaskListItem() {
        val blocks = parseMarkdown("1. [x] first step\n2. [ ] second step")
        val first = blocks[0] as MdBlock.ListItem
        val second = blocks[1] as MdBlock.ListItem
        assertEquals(true, first.checked)
        assertEquals(false, second.checked)
    }

    @Test
    fun nonTaskListItemHasNullChecked() {
        val blocks = parseMarkdown("- regular item")
        val item = blocks[0] as MdBlock.ListItem
        assertNull(item.checked)
    }

    // ---- safeHttpUrl ----------------------------------------------------------

    @Test
    fun safeHttpUrlAcceptsHttpAndHttps() {
        assertEquals("https://example.com", safeHttpUrl("https://example.com"))
        assertEquals("http://localhost:3080", safeHttpUrl("http://localhost:3080"))
        assertEquals("https://example.com/path?q=1", safeHttpUrl("  https://example.com/path?q=1  "))
    }

    @Test
    fun safeHttpUrlRejectsOtherSchemes() {
        assertNull(safeHttpUrl("javascript:alert(1)"))
        assertNull(safeHttpUrl("file:///etc/passwd"))
        assertNull(safeHttpUrl("ftp://example.com"))
        assertNull(safeHttpUrl("mailto:user@example.com"))
        assertNull(safeHttpUrl(""))
    }

    @Test
    fun safeHttpUrlRejectsEmptyAndWhitespace() {
        assertNull(safeHttpUrl(""))
        assertNull(safeHttpUrl("   "))
    }
}
