package com.labteto.dshmobile.ui.screens.main.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxTokensTest {

    private fun spans(line: String, language: String) =
        tokenizeLine(line, language).map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun `a c style line has keywords, strings, numbers, calls and a trailing comment`() {
        val line = """val name = greet("hi", 42) // say hello"""
        assertEquals(
            listOf(
                "val" to TokenKind.Keyword,
                "greet" to TokenKind.Function,
                "\"hi\"" to TokenKind.String,
                "42" to TokenKind.Number,
                "// say hello" to TokenKind.Comment,
            ),
            spans(line, "kt"),
        )
    }

    @Test
    fun `a comment marker inside a string is not a comment`() {
        assertEquals(-1, commentStart("""url = "http://example.com/#x"""", listOf("//", "#")))
        assertEquals(9, commentStart("""name = 1 # tail""", listOf("#")))
        val tokens = spans("""path = "a#b" # real""", "py")
        assertEquals("# real" to TokenKind.Comment, tokens.last())
        assertTrue(tokens.contains("\"a#b\"" to TokenKind.String))
    }

    @Test
    fun `block comment lines are comments`() {
        assertEquals(listOf("/** Docs */" to TokenKind.Comment), spans("/** Docs */", "java"))
        assertEquals(listOf("* continued" to TokenKind.Comment), spans("   * continued", "java"))
        val inline = spans("int /* note */ x = 1;", "c")
        assertTrue(inline.contains("/* note */" to TokenKind.Comment))
        assertTrue(inline.contains("1" to TokenKind.Number))
    }

    @Test
    fun `hash style languages colour hash comments and ignore slashes`() {
        assertEquals(listOf("# note" to TokenKind.Comment), spans("# note", "sh"))
        assertEquals(emptyList<Pair<String, TokenKind>>(), spans("// not a comment here", "py").filter { it.second == TokenKind.Comment })
    }

    @Test
    fun `yaml keys and comments`() {
        val tokens = spans("  name: build # step", "yml")
        assertEquals("name" to TokenKind.Key, tokens.first())
        assertEquals("# step" to TokenKind.Comment, tokens.last())
        // A hash inside a word, or inside quotes, is not a comment (a hash after a space is, in YAML).
        assertFalse(spans("tag: a#b", "yaml").any { it.second == TokenKind.Comment })
        assertFalse(spans("color: \"#fff\"", "yaml").any { it.second == TokenKind.Comment })
        assertEquals("run" to TokenKind.Key, spans("- run: make", "yaml").first())
    }

    @Test
    fun `ini and toml sections and keys`() {
        assertEquals(listOf("[server]" to TokenKind.Function), spans("[server]", "toml"))
        val keyed = spans("port = 8080", "ini")
        assertEquals("port" to TokenKind.Key, keyed.first())
        assertEquals("8080" to TokenKind.Number, keyed.last())
        assertEquals(listOf("; a note" to TokenKind.Comment), spans("; a note", "ini"))
        assertEquals("key" to TokenKind.Key, spans("key=value", "properties").first())
    }

    @Test
    fun `dockerfile instructions and makefile targets`() {
        assertEquals("FROM" to TokenKind.Keyword, spans("FROM node:20", "dockerfile").first())
        assertEquals("build" to TokenKind.Function, spans("build: deps", "makefile").first())
        assertFalse(spans("\tgo build ./...", "makefile").any { it.second == TokenKind.Function && it.first == "go" })
    }

    @Test
    fun `sql keywords are case blind`() {
        val tokens = spans("SELECT id FROM users WHERE id = 1 -- first", "sql")
        assertEquals(listOf("SELECT", "FROM", "WHERE"), tokens.filter { it.second == TokenKind.Keyword }.map { it.first })
        assertEquals("-- first" to TokenKind.Comment, tokens.last())
    }

    @Test
    fun `markup tags, attributes, values and comments`() {
        val tokens = spans("""<a href="x.html" id='y'>text</a>""", "xml")
        assertEquals("<a" to TokenKind.Keyword, tokens.first())
        assertTrue(tokens.contains("href" to TokenKind.Attribute))
        assertTrue(tokens.contains("\"x.html\"" to TokenKind.String))
        assertTrue(tokens.contains("</a" to TokenKind.Keyword))
        assertEquals(listOf("<!-- hi -->" to TokenKind.Comment), spans("<!-- hi -->", "html"))
    }

    @Test
    fun `plain text and unknown languages get no colour`() {
        assertEquals(emptyList<Token>(), tokenizeLine("val x = 1", "txt"))
        assertEquals(emptyList<Token>(), tokenizeLine("   ", "kt"))
        assertFalse(isHighlighted("txt"))
        assertTrue(isHighlighted("kt"))
        assertTrue(isHighlighted("json"))
    }

    @Test
    fun `tokens never overlap and stay in order`() {
        val lines = listOf(
            """val s = "a" + 'b' /* c */ + d(1) // e""",
            """  name: "x" # y""",
            """<div class="a" data-x='1'><!-- c --></div>""",
            """{"k": [1, 2.5e3, true, null, "v"]}""",
        )
        val languages = listOf("kt", "yml", "html", "json")
        lines.zip(languages).forEach { (line, language) ->
            var previousEnd = 0
            for (token in tokenizeLine(line, language)) {
                assertTrue("$language: $token starts before $previousEnd", token.start >= previousEnd)
                assertTrue(token.end > token.start && token.end <= line.length)
                previousEnd = token.end
            }
        }
    }
}
