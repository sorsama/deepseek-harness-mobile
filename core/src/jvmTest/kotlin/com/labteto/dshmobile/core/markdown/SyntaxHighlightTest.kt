package com.labteto.dshmobile.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lexer's contract, in the two parts the renderer depends on: the spans must reproduce the
 * input exactly (it lays them out as plain text), and the tokens must actually be recognized
 * (otherwise highlighting is silently doing nothing).
 */
class SyntaxHighlightTest {

    /** The property the renderer relies on: highlighting never changes the code's characters. */
    private fun roundTrips(code: String, lang: String?) {
        val rebuilt = highlightCode(code, lang).joinToString("\n") { line -> line.joinToString("") { it.text } }
        assertEquals(code, rebuilt)
    }

    private fun tokensOf(line: String, lang: String?, token: SyntaxToken): List<String> =
        highlightCode(line, lang).single()
            .filter { it.token == token }
            .map { it.text }

    @Test
    fun spansReproduceTheInput() {
        val code = "fun main() {\n  val x = \"hi\" // note\n  println(x)\n}"
        roundTrips(code, "kotlin")
        roundTrips(code, null)
        roundTrips(code, "brainfuck")
        roundTrips("", "python")
    }

    @Test
    fun recognizesKeywordsAndStrings() {
        val code = "val name = \"dsh\""
        assertEquals(listOf("val"), tokensOf(code, "kotlin", SyntaxToken.Keyword))
        assertEquals(listOf("\"dsh\""), tokensOf(code, "kotlin", SyntaxToken.String))
    }

    @Test
    fun recognizesLineComments() {
        val code = "val x = 1 // trailing"
        assertEquals(listOf("// trailing"), tokensOf(code, "kotlin", SyntaxToken.Comment))
        // Python and bash use #, not //.
        assertEquals(listOf("# note"), tokensOf("# note", "python", SyntaxToken.Comment))
        assertEquals(listOf("# note"), tokensOf("# note", "bash", SyntaxToken.Comment))
    }

    @Test
    fun blockCommentStateCarriesAcrossLines() {
        val lines = highlightCode("/* one\nstill comment\n*/ val x = 1", "kotlin")
        assertEquals(SyntaxToken.Comment, lines[0].single().token)
        // The middle line is entirely inside the comment.
        assertEquals(SyntaxToken.Comment, lines[1].single().token)
        // The closing line ends the comment and then lexes real code.
        assertTrue(lines[2].any { it.token == SyntaxToken.Comment })
        assertTrue(lines[2].any { it.token == SyntaxToken.Keyword && it.text == "val" })
        roundTrips("/* one\nstill comment\n*/ val x = 1", "kotlin")
    }

    @Test
    fun aBlockCommentThatNeverClosesDoesNotLeakPastTheBlock() {
        // Each call is independent: the state machine is per invocation, not global.
        val lines = highlightCode("/* open forever", "kotlin")
        assertEquals(SyntaxToken.Comment, lines[0].single().token)
        // A fresh call is unaffected by the previous one's open comment.
        assertEquals(listOf("val"), tokensOf("val x = 1", "kotlin", SyntaxToken.Keyword))
    }

    @Test
    fun callNamesAndTypesGetTheirOwnTokens() {
        assertEquals(listOf("println"), tokensOf("println(x)", "kotlin", SyntaxToken.Function))
        // A capitalized word *not* followed by `(` is a type; followed by one it is a call, which
        // is the precedence the lexer documents and what a reader scanning for calls wants.
        assertEquals(listOf("StringBuilder"), tokensOf("val b: StringBuilder", "kotlin", SyntaxToken.Type))
        assertEquals(listOf("StringBuilder"), tokensOf("StringBuilder()", "kotlin", SyntaxToken.Function))
        assertEquals(listOf("42", "3.5"), tokensOf("42 3.5", "kotlin", SyntaxToken.Number))
    }

    @Test
    fun sqlKeywordsMatchCaseInsensitively() {
        assertEquals(listOf("select"), tokensOf("select 1", "sql", SyntaxToken.Keyword))
        assertEquals(listOf("SELECT"), tokensOf("SELECT 1", "sql", SyntaxToken.Keyword))
        // Kotlin stays case-sensitive: `Val` is a type name, not the keyword.
        assertEquals(emptyList<String>(), tokensOf("Val x", "kotlin", SyntaxToken.Keyword))
    }

    @Test
    fun unknownOrPlainLanguagesAreLeftAlone() {
        assertFalse(isHighlighted(null))
        assertFalse(isHighlighted(""))
        assertFalse(isHighlighted("brainfuck"))
        assertFalse(isHighlighted("text"))
        assertTrue(isHighlighted("kotlin"))
        assertTrue(isHighlighted("kt"))
        assertTrue(isHighlighted("KOTLIN"))

        // Nothing is tagged, so the renderer can skip building spans entirely.
        val spans = highlightCode("fun main() {}", "brainfuck")
        assertEquals(listOf(SyntaxToken.Plain), spans.single().map { it.token })
        roundTrips("fun main() {}", "brainfuck")
    }

    @Test
    fun aliasesResolveToTheCanonicalLexer() {
        assertEquals("kotlin", normalizeLanguage("kt"))
        assertEquals("typescript", normalizeLanguage("ts"))
        assertEquals("typescript", normalizeLanguage("javascript"))
        assertEquals("bash", normalizeLanguage("sh"))
        assertEquals("python", normalizeLanguage("py"))
        assertEquals("yaml", normalizeLanguage("yml"))
        assertEquals("xml", normalizeLanguage("html"))
        assertNull(normalizeLanguage("  "))
    }

    @Test
    fun adjacentSpansOfTheSameTokenAreMerged() {
        // Four plain characters should not become four spans: the renderer builds one per span.
        val spans = highlightCode("    val x = 1", "kotlin").single()
        assertTrue("expected the run of spaces to merge", spans.first().text.length >= 4)
        roundTrips("    val x = 1", "kotlin")
    }
}
