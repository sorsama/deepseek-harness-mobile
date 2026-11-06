package com.labteto.dshmobile.ui.screens.main.preview

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonDocsTest {

    private fun valid(text: String, lines: Boolean = false) = parseJsonText(text, lines) as JsonParse.Valid
    private fun invalid(text: String, lines: Boolean = false) = parseJsonText(text, lines) as JsonParse.Invalid

    @Test
    fun `an object parses and pretty prints with two spaces`() {
        val pretty = prettyJson(valid("""{"a":1,"b":[true,null,"x"],"c":{}}"""))
        assertEquals(
            "{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null,\n    \"x\"\n  ],\n  \"c\": {}\n}",
            pretty,
        )
    }

    @Test
    fun `plain numbers keep their spelling and an exponent is written out`() {
        val pretty = prettyJson(valid("""{"n":1.0,"big":12345678901234567890,"e":1e5}"""))
        assertTrue(pretty.contains("\"n\": 1.0"))
        // Larger than a long: still exact, because the number is never converted.
        assertTrue(pretty.contains("12345678901234567890"))
        // The parser reads an exponent as a double, so the pretty view shows its plain form; Raw keeps the original text.
        assertTrue(pretty, pretty.contains("\"e\": 100000.0"))
    }

    @Test
    fun `comments and trailing commas are tolerated`() {
        val result = valid("{\n // note\n \"a\": 1, /* inline */\n \"b\": [1, 2,],\n}")
        assertEquals(2, (result.root as JsonObject).size)
    }

    @Test
    fun `a byte order mark is ignored`() {
        assertTrue(parseJsonText(BYTE_ORDER_MARK + "[1]", false) is JsonParse.Valid)
    }

    @Test
    fun `invalid json says where it went wrong`() {
        val problem = invalid("{\n  \"a\": 1,\n  \"b\" 2\n}")
        assertEquals(3, problem.line)
        assertNotNull(problem.column)
        assertTrue(problem.reason.isNotBlank())
    }

    @Test
    fun `a blank file is invalid without a position`() {
        val problem = invalid("   \n")
        assertNull(problem.line)
        assertEquals("", problem.reason)
    }

    @Test
    fun `json lines read one value per line and skip blank lines`() {
        val result = valid("{\"a\":1}\n\n{\"a\":2}\n[3]\n", lines = true)
        assertTrue(result.lineDelimited)
        assertEquals(3, (result.root as JsonArray).size)
        val pretty = prettyJson(result)
        assertTrue(pretty.contains("\n\n"))
    }

    @Test
    fun `a bad line in json lines is named by its line number`() {
        val problem = invalid("{\"a\":1}\n{oops}\n{\"a\":3}", lines = true)
        assertEquals(2, problem.line)
    }

    @Test
    fun `line and column are one based`() {
        assertEquals(1 to 1, lineColumnOf("abc", 0))
        assertEquals(1 to 3, lineColumnOf("abc", 2))
        assertEquals(2 to 1, lineColumnOf("ab\ncd", 3))
        assertEquals(2 to 3, lineColumnOf("ab\ncd", 5))
    }

    @Test
    fun `the tree starts at the root and opens containers on demand`() {
        val root = valid("""{"name":"x","items":[1,{"deep":true}],"empty":[]}""").root
        val closed = flattenJson(root, emptySet())
        assertEquals(1, closed.size)
        assertEquals(JsonNodeKind.Object, closed[0].kind)
        assertEquals("{3}", closed[0].value)
        assertTrue(closed[0].expandable)
        assertFalse(closed[0].expanded)

        val open = flattenJson(root, setOf("$"))
        assertEquals(listOf("$", "$/name", "$/items", "$/empty"), open.map { it.id })
        assertEquals("\"x\"", open[1].value)
        assertEquals(JsonNodeKind.String, open[1].kind)
        assertEquals("[2]", open[2].value)
        assertFalse(open[3].expandable)

        val deeper = flattenJson(root, setOf("$", "$/items", "$/items/1"))
        assertEquals(listOf("$", "$/name", "$/items", "$/items/0", "$/items/1", "$/items/1/deep", "$/empty"), deeper.map { it.id })
        assertEquals(JsonNodeKind.Bool, deeper[5].kind)
        assertEquals(3, deeper[5].depth)
    }

    @Test
    fun `scalar kinds are told apart`() {
        val root = valid("""{"s":"1","n":1,"t":true,"z":null}""").root
        val rows = flattenJson(root, setOf("$")).drop(1)
        assertEquals(
            listOf(JsonNodeKind.String, JsonNodeKind.Number, JsonNodeKind.Bool, JsonNodeKind.Null),
            rows.map { it.kind },
        )
    }

    @Test
    fun `a long string is shortened in the tree`() {
        val root = valid("""{"s":"${"x".repeat(1000)}"}""").root
        val row = flattenJson(root, setOf("$"))[1]
        assertTrue(row.value.length < 400)
        assertTrue(row.value.endsWith("…\""))
    }

    @Test
    fun `a big document is cut with a note about how much was left out`() {
        val root = valid("[" + (1..50).joinToString(",") + "]").root
        val rows = flattenJson(root, setOf("$"), maxRows = 10)
        assertEquals(11, rows.size)
        assertEquals(JsonNodeKind.More, rows.last().kind)
        assertEquals("41", rows.last().value)
    }

    @Test
    fun `json tokens colour keys apart from string values`() {
        val tokens = tokenizeJsonLine("""  "name": "Ada", "n": -1.5e3, "ok": true,""")
        val kinds = tokens.map { it.kind }
        assertEquals(
            listOf(TokenKind.Key, TokenKind.String, TokenKind.Key, TokenKind.Number, TokenKind.Key, TokenKind.Keyword),
            kinds,
        )
        val first = tokens.first()
        assertEquals("\"name\"", """  "name": "Ada", "n": -1.5e3, "ok": true,""".substring(first.start, first.end))
    }

    @Test
    fun `an escaped quote does not end a json string early`() {
        val line = """"k": "a\"b""""
        val tokens = tokenizeJsonLine(line)
        assertEquals(2, tokens.size)
        assertEquals("\"a\\\"b\"", line.substring(tokens[1].start, tokens[1].end))
    }
}
