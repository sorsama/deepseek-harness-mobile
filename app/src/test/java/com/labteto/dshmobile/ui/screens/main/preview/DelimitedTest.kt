package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedTest {

    private fun parse(text: String, delimiter: Char = ',', keep: Int = Int.MAX_VALUE) = parseDelimited(text, delimiter, keep)

    @Test
    fun `plain rows and fields`() {
        val table = parse("a,b,c\n1,2,3\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table.rows)
        assertEquals(2, table.totalRecords)
        assertEquals(3, table.columns)
    }

    @Test
    fun `quoted fields hold delimiters, quotes and line breaks`() {
        val table = parse("name,note\n\"Smith, Jo\",\"said \"\"hi\"\"\"\nx,\"line one\nline two\"\n")
        assertEquals(listOf("Smith, Jo", "said \"hi\""), table.rows[1])
        assertEquals(listOf("x", "line one\nline two"), table.rows[2])
        assertEquals(3, table.totalRecords)
    }

    @Test
    fun `all line endings end a record`() {
        assertEquals(3, parse("a\r\nb\r\nc").totalRecords)
        assertEquals(3, parse("a\rb\rc\r").totalRecords)
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parse("a,b\r\nc,d\r\n").rows)
    }

    @Test
    fun `blank lines are not records but an explicit empty string is`() {
        assertEquals(2, parse("a\n\n\nb\n").totalRecords)
        assertEquals(listOf(listOf("")), parse("\"\"\n").rows)
    }

    @Test
    fun `empty fields and a trailing delimiter keep their cells`() {
        assertEquals(listOf(listOf("a", "", "c")), parse("a,,c").rows)
        assertEquals(listOf(listOf("a", "b", "")), parse("a,b,").rows)
        assertEquals(listOf(listOf("", "")), parse(",").rows)
    }

    @Test
    fun `an unterminated quote runs to the end instead of failing`() {
        val table = parse("a,\"never closed\nstill going")
        assertEquals(listOf("a", "never closed\nstill going"), table.rows[0])
    }

    @Test
    fun `a quote in the middle of an unquoted field is literal`() {
        assertEquals(listOf(listOf("5\" pipe", "ok")), parse("5\" pipe,ok").rows)
    }

    @Test
    fun `text after a closing quote is kept`() {
        assertEquals(listOf(listOf("ab", "c")), parse("\"a\"b,c").rows)
    }

    @Test
    fun `rows past the cap are counted but not kept`() {
        val text = (1..50).joinToString("\n") { "r$it,x" }
        val table = parse(text, keep = 10)
        assertEquals(10, table.rows.size)
        assertEquals(50, table.totalRecords)
        assertTrue(table.truncated)
        assertEquals("r10", table.rows.last()[0])
        assertFalse(parse(text).truncated)
    }

    @Test
    fun `ragged rows widen the grid`() {
        val table = parse("a,b\n1,2,3,4\n5\n")
        assertEquals(4, table.columns)
    }

    @Test
    fun `other delimiters work the same way`() {
        assertEquals(listOf(listOf("a", "b;c")), parse("a;\"b;c\"", ';').rows)
        assertEquals(listOf(listOf("a", "b c")), parse("a\tb c", '\t').rows)
    }

    @Test
    fun `the delimiter is found from the first record`() {
        assertEquals(',', detectDelimiter("a,b,c\n1,2,3", "csv"))
        assertEquals(';', detectDelimiter("a;b;c\n1;2;3", "csv"))
        assertEquals('\t', detectDelimiter("a\tb\tc\n", "csv"))
        assertEquals('|', detectDelimiter("a|b|c\n", "csv"))
        assertEquals('\t', detectDelimiter("a,b,c\n", "tsv"))
        assertEquals(',', detectDelimiter("single column\nnext", "csv"))
        // A delimiter inside quotes does not count.
        assertEquals(',', detectDelimiter("\"a;b;c;d\",e,f\n", "csv"))
    }

    @Test
    fun `column widths follow the content within bounds`() {
        val rows = listOf(listOf("id", "name", "a very long cell indeed that keeps going and going and going"), listOf("12345", "Al", "x"))
        val widths = columnWidths(rows, 3, minChars = 4, maxChars = 20)
        assertEquals(listOf(5, 4, 20), widths.toList())
    }
}
