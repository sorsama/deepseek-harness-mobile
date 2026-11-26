package com.labteto.dshmobile.ui.components

import com.labteto.dshmobile.core.markdown.isHighlighted
import com.labteto.dshmobile.ui.theme.DsThemeTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line model of a file read, as the gutter block draws it: one entry per line the read recorded,
 * numbered as it recorded them, in step with its code by construction. (A blank line was a shorter row
 * in the code column than in the number column, so by the seventeenth line the number sat a third of
 * a row below its code; the block now holds a number and its code in one row.)
 *
 * The read's lines come from the harness already cut ([ReadLine]); how a whole text is cut into lines
 * (trailing break, CRLF, huge lines) is [LineIndex]'s, with its own tests.
 */
class GutterLinesTest {
    private val colors = DsThemeTokens.dark

    private fun read(vararg texts: String, from: Int = 1) = texts.mapIndexed { i, text -> ReadLine(from + i, text) }

    private fun model(lines: List<ReadLine>, lang: String? = null) = readGutterLines(lines, lang, colors)

    private fun List<GutterLine>.texts() = map { it.text.text }

    private fun List<GutterLine>.numbers() = map { it.cells.single().text }

    @Test
    fun `every line is a row and a blank line is one too`() {
        // A Kotlin file's shape: package, blank, imports, blank, blank, code.
        val rows = model(read("package a", "", "import b", "", "", "fun x() = 1"))
        assertEquals(6, rows.size)
        assertEquals(listOf("package a", "", "import b", "", "", "fun x() = 1"), rows.texts())
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), rows.numbers())
    }

    @Test
    fun `a file of only blank lines keeps every line`() {
        val rows = model(read("", "", ""))
        assertEquals(listOf("", "", ""), rows.texts())
        assertEquals(listOf("1", "2", "3"), rows.numbers())
    }

    @Test
    fun `a trailing break may or may not be a last empty line and is never added or dropped`() {
        // "a\nb\n" as a read may report it: two lines, or two and an empty third.
        assertEquals(listOf("a", "b"), model(read("a", "b")).texts())
        val withBlankLast = model(read("a", "b", ""))
        assertEquals(listOf("a", "b", ""), withBlankLast.texts())
        assertEquals(listOf("1", "2", "3"), withBlankLast.numbers())
        // The same with a language the lexer colours: the blank last line must not cost the colour of the rest.
        val colored = model(read("val a = 1", "val b = 2", ""), lang = "kotlin")
        assertEquals(listOf("val a = 1", "val b = 2", ""), colored.texts())
        assertTrue(colored[0].text.spanStyles.isNotEmpty())
    }

    @Test
    fun `a file that ends without a break keeps its last line`() {
        val rows = model(read("first", "last, no newline after it"))
        assertEquals(listOf("first", "last, no newline after it"), rows.texts())
    }

    @Test
    fun `a carriage return left by a CRLF file is not drawn`() {
        val rows = model(read("alpha\r", "\r", "beta\r"))
        assertEquals(listOf("alpha", "", "beta"), rows.texts())
        assertTrue(rows.texts().none { it.contains('\r') })
        // And it does not move the colour off its line either.
        val kotlin = model(read("val a = 1\r", "\r", "val b = 2\r"), lang = "kotlin")
        assertEquals(listOf("val a = 1", "", "val b = 2"), kotlin.texts())
        assertTrue(kotlin[2].text.spanStyles.isNotEmpty())
    }

    @Test
    fun `a very long line stays one row, whole`() {
        val long = "x".repeat(50_000)
        val rows = model(read("short", long, "short again"))
        assertEquals(3, rows.size)
        assertEquals(50_000, rows[1].text.length)
        assertEquals(listOf("1", "2", "3"), rows.numbers())
    }

    @Test
    fun `numbers are the ones the read recorded, not the row's position`() {
        // A read of lines 40 to 43 of a longer file.
        val rows = model(read("a", "", "b", "c", from = 40))
        assertEquals(listOf("40", "41", "42", "43"), rows.numbers())
    }

    @Test
    fun `a block comment colours the lines it spans and a blank line inside it keeps its place`() {
        val rows = model(read("/* one", "", "two */ val x = 1", ""), lang = "kotlin")
        assertEquals(listOf("/* one", "", "two */ val x = 1", ""), rows.texts())
        // Colour on the comment line and on the keyword after the comment's end.
        assertTrue(rows[0].text.spanStyles.isNotEmpty())
        assertTrue(rows[2].text.spanStyles.isNotEmpty())
    }

    @Test
    fun `an empty read has no rows`() {
        assertTrue(model(emptyList()).isEmpty())
        assertTrue(highlightedLines(emptyList(), "kotlin", colors).isEmpty())
    }

    @Test
    fun `the highlighter has no Markdown mode so a Markdown file is plain`() {
        assertFalse(isHighlighted("md"))
        assertFalse(isHighlighted("markdown"))
        val rows = model(read("# docs-site", "", "The documentation for the **Harbor** CLI."), lang = "md")
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.text.spanStyles.isEmpty() })
        // Kotlin and JSON, which the read cards do colour, have rules.
        assertTrue(isHighlighted("kt"))
        assertTrue(isHighlighted("json"))
    }
}
