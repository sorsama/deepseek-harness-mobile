package com.labteto.dshmobile.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer's line index: correct on small texts, and cheap on the huge ones it exists for. */
class LineIndexTest {

    private fun lines(text: String, max: Int = LineIndex.DEFAULT_MAX_LINE_CHARS): List<String> {
        val index = LineIndex.of(text, max)
        return (0 until index.count).map(index::line)
    }

    @Test
    fun `lines split on newlines and a trailing break adds no empty line`() {
        assertEquals(listOf("a", "b", "c"), lines("a\nb\nc"))
        assertEquals(listOf("a", "b"), lines("a\nb\n"))
        assertEquals(listOf("a", "", "b"), lines("a\n\nb"))
        assertEquals(listOf("a", ""), lines("a\n\n"))
        assertEquals(emptyList<String>(), lines(""))
        assertEquals(listOf(""), lines("\n"))
    }

    @Test
    fun `a carriage return before the newline is not part of the line`() {
        assertEquals(listOf("one", "two", "three"), lines("one\r\ntwo\r\nthree\r"))
    }

    @Test
    fun `a long line continues on the next display line and joins back whole`() {
        val long = "x".repeat(25)
        val pieces = lines("head\n$long\ntail", max = 10)
        assertEquals(listOf("head", "xxxxxxxxxx", "xxxxxxxxxx", "xxxxx", "tail"), pieces)
        assertTrue(pieces.all { it.length <= 10 })
    }

    @Test
    fun `a long line is not cut between the halves of a surrogate pair`() {
        // 11 emoji = 22 chars: a 5 char limit would split pairs if it cut blindly.
        val pieces = lines("😀".repeat(11), max = 5)
        assertEquals("😀".repeat(11), pieces.joinToString(""))
        assertTrue(pieces.all { piece -> piece.length <= 5 && !piece.last().isHighSurrogate() && !piece.first().isLowSurrogate() })
    }

    @Test
    fun `lineAt maps a character offset to its display line`() {
        val index = LineIndex.of("ab\ncd\nef")
        assertEquals(0, index.lineAt(0))
        assertEquals(0, index.lineAt(1))
        assertEquals(1, index.lineAt(3))
        assertEquals(2, index.lineAt(7))
        assertEquals(2, index.lineAt(100))
        assertEquals(3, index.startOf(1))
    }

    @Test
    fun `a search lists each matching line once and ignores case`() {
        val index = LineIndex.of("alpha\nBeta gamma\nGAMMA gamma\ndelta\nGamma")
        assertArrayEquals(intArrayOf(1, 2, 4), index.linesContaining("gamma"))
        assertArrayEquals(IntArray(0), index.linesContaining("zeta"))
        assertArrayEquals(IntArray(0), index.linesContaining(""))
        assertArrayEquals(IntArray(0), index.linesContaining("a\nb"))
    }

    @Test
    fun `a search stops at its limit`() {
        val index = LineIndex.of((1..100).joinToString("\n") { "hit $it" })
        assertEquals(7, index.linesContaining("hit", limit = 7).size)
        assertEquals(100, index.linesContaining("hit").size)
    }

    @Test
    fun `occurrences marks each hit in a line without overlap`() {
        assertEquals(listOf(0..2, 8..10), occurrences("Foo bar foo", "foo"))
        assertEquals(listOf(0..1, 2..3), occurrences("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), occurrences("abc", ""))
        assertEquals(emptyList<IntRange>(), occurrences("ab", "abc"))
    }

    @Test
    fun `a 5 MB single line becomes bounded display lines without losing text`() {
        val text = "0123456789".repeat(500_000)
        assertEquals(5_000_000, text.length)
        val index = LineIndex.of(text)
        assertEquals(5_000_000 / LineIndex.DEFAULT_MAX_LINE_CHARS, index.count)
        // Every display line is at most the limit, so no single Text is asked to lay out megabytes.
        for (i in 0 until index.count step 97) assertTrue(index.line(i).length <= LineIndex.DEFAULT_MAX_LINE_CHARS)
        // And the pieces are the whole text, in order.
        val joined = StringBuilder(text.length)
        for (i in 0 until index.count) joined.append(index.line(i))
        assertEquals(text, joined.toString())
    }

    @Test
    fun `a 200000 line output indexes line by line and finds a needle`() {
        val text = buildString {
            for (n in 1..200_000) {
                append("line ").append(n)
                if (n == 150_000) append(" NEEDLE")
                append('\n')
            }
        }
        val index = LineIndex.of(text)
        assertEquals(200_000, index.count)
        assertEquals("line 1", index.line(0))
        assertEquals("line 200000", index.line(199_999))
        assertArrayEquals(intArrayOf(149_999), index.linesContaining("needle"))
        assertEquals(149_999, index.lineAt(text.indexOf("NEEDLE")))
    }

    @Test
    fun `a search in 5 MB of text with a hit on every line stops at the default limit`() {
        val text = (1..100_000).joinToString("\n") { "match $it " + "y".repeat(40) }
        val index = LineIndex.of(text)
        assertTrue(text.length > 4_000_000)
        assertEquals(LineIndex.DEFAULT_MATCH_LIMIT, index.linesContaining("match").size)
    }
}
