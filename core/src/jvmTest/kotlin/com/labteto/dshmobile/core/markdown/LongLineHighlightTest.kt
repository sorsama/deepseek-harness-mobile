package com.labteto.dshmobile.core.markdown

import org.junit.Assert.*
import org.junit.Test

class LongLineHighlightTest {
    @Test fun `generated lines stay lossless plain text and following source still highlights`() {
        val generated = "const a=1;".repeat(10_000)
        val code = "/* comment\n$generated\nconst next = 2;"
        val lines = highlightCode(code, "js")
        assertEquals(listOf(SyntaxSpan(generated, SyntaxToken.Plain)), lines[1])
        assertTrue(lines[2].any { it.token == SyntaxToken.Keyword && it.text == "const" })
        assertEquals(code, lines.joinToString("\n") { spans -> spans.joinToString("") { it.text } })
    }
}
