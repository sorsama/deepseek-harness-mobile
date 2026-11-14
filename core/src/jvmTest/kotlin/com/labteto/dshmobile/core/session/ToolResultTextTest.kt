package com.labteto.dshmobile.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultTextTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    private fun read(json: String) = readToolResult(parse(json))!!

    /** The text of one typed block, built as the wire builds it so quoting is never hand-escaped. */
    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    @Test
    fun typedTextArrayShowsTheTextAndKeepsTheRawJson() {
        val reading = read("""[{"type":"text","text":"build finished in 12s"}]""")
        assertEquals("build finished in 12s", reading.text)
        assertFalse(reading.structured)
        // The raw action has something to show: the wrapper the text came out of.
        val raw = reading.rawJson!!
        assertTrue(raw, raw.contains("\"type\": \"text\""))
        assertTrue(raw, raw.contains("build finished in 12s"))
    }

    @Test
    fun severalTextBlocksJoinWithNewlines() {
        val reading = read("""[{"type":"text","text":"one"},{"type":"text","text":"two"}]""")
        assertEquals("one\ntwo", reading.text)
    }

    @Test
    fun textKeepsItsOwnLineBreaksAndWhitespace() {
        val reading = readToolResult(JsonArray(listOf(textBlock("  a\n\tb\n"))))!!
        assertEquals("  a\n\tb\n", reading.text)
    }

    @Test
    fun contentEnvelopeIsUnwrappedAndBookkeepingKeysDoNotBlockIt() {
        val reading = read(
            """{"content":[{"type":"text","text":"done"}],"isError":false,"structuredContent":{"ok":true}}""",
        )
        assertEquals("done", reading.text)
        assertFalse(reading.structured)
        assertNotNull(reading.rawJson)
    }

    @Test
    fun toolResultWrapperIsUnwrapped() {
        val reading = read("""{"type":"tool-result","toolCallId":"c1","content":[{"type":"text","text":"ok"}]}""")
        assertEquals("ok", reading.text)
    }

    @Test
    fun outputEnvelopeIsUnwrapped() {
        assertEquals("compiled 3 files", read("""{"output":"compiled 3 files"}""").text)
        assertEquals("a\nb", read("""{"output":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""").text)
    }

    @Test
    fun textThatIsItselfABlockListIsUnwrapped() {
        // The shape behind the issue: the tool's text is the JSON of another block list.
        val inner = buildJsonArray { add(textBlock("BUILD SUCCESSFUL in 41s")) }.toString()
        val reading = readToolResult(JsonArray(listOf(textBlock(inner))))!!
        assertEquals("BUILD SUCCESSFUL in 41s", reading.text)
        assertFalse(reading.structured)
    }

    @Test
    fun textThatIsAnEnvelopeObjectIsUnwrapped() {
        val inner = buildJsonObject { put("content", buildJsonArray { add(textBlock("hello")) }) }.toString()
        assertEquals("hello", readToolResult(JsonArray(listOf(textBlock(inner))))!!.text)
    }

    @Test
    fun repeatedlyDoubleEncodedTextIsUnwrappedToTheEnd() {
        var wrapped = "the log line"
        repeat(3) { wrapped = buildJsonArray { add(textBlock(wrapped)) }.toString() }
        assertEquals("the log line", readToolResult(JsonPrimitive(wrapped))!!.text)
        assertEquals("the log line", readToolResult(JsonArray(listOf(textBlock(wrapped))))!!.text)
    }

    @Test
    fun textNestedAsAValueRatherThanAStringIsUnwrapped() {
        val reading = read("""[{"type":"text","text":{"type":"text","text":"deep"}}]""")
        assertEquals("deep", reading.text)
        val viaEnvelope = read("""[{"type":"text","text":{"content":[{"type":"text","text":"deeper"}]}}]""")
        assertEquals("deeper", viaEnvelope.text)
    }

    @Test
    fun bareStringIsShownAsItIsWithNothingToExpand() {
        val reading = read("\"plain output\"")
        assertEquals("plain output", reading.text)
        assertNull(reading.rawJson)
    }

    @Test
    fun bareStringHoldingAnEnvelopeIsUnwrappedAndTheOriginalStaysAsRaw() {
        val original = """[{"type":"text","text":"inside"}]"""
        val reading = readToolResult(JsonPrimitive(original))!!
        assertEquals("inside", reading.text)
        assertEquals(original, reading.rawJson)
    }

    @Test
    fun genuineObjectIsPrettyPrintedNotFlattened() {
        val reading = read("""{"jobId":"j1","status":"running","pid":4242}""")
        assertTrue(reading.structured)
        assertEquals("{\n  \"jobId\": \"j1\",\n  \"status\": \"running\",\n  \"pid\": 4242\n}", reading.text)
        // The text already is the JSON, so a "raw" action would only repeat it.
        assertNull(reading.rawJson)
    }

    @Test
    fun envelopeWithOtherFieldsIsARealResultAndKeepsEveryField() {
        val reading = read("""{"output":"build ok","exitCode":2}""")
        assertTrue(reading.structured)
        assertTrue(reading.text, reading.text.contains("\"exitCode\": 2"))
        assertTrue(reading.text, reading.text.contains("\"output\": \"build ok\""))
    }

    @Test
    fun listOfNumbersOrRecordsIsDataNotText() {
        val numbers = read("[1,2,3]")
        assertTrue(numbers.structured)
        assertEquals("[\n  1,\n  2,\n  3\n]", numbers.text)
        val records = read("""[{"path":"a.kt"},{"path":"b.kt"}]""")
        assertTrue(records.structured)
        assertTrue(records.text, records.text.contains("\"path\": \"a.kt\""))
    }

    @Test
    fun aTextBlockThatOnlyLooksLikeJsonStaysVerbatim() {
        val record = """{"name":"dsh","version":"0.2.0"}"""
        assertEquals(record, read("""[{"type":"text","text":${JsonPrimitive(record)}}]""").text)
        assertEquals("[1,2,3]", read("""[{"type":"text","text":"[1,2,3]"}]""").text)
        assertEquals("[INFO] building", read("""[{"type":"text","text":"[INFO] building"}]""").text)
        assertEquals("{not json}", read("""[{"type":"text","text":"{not json}"}]""").text)
    }

    @Test
    fun imageBlocksAreLeftToTheTranscriptAndOtherBlocksArePrettyPrinted() {
        val withImage = read(
            """[{"type":"text","text":"shot"},{"type":"image","attachment":{"attachmentId":"a1"}}]""",
        )
        assertEquals("shot", withImage.text)
        assertFalse(withImage.structured)

        val imageOnly = read("""[{"type":"image","attachment":{"attachmentId":"a1"}}]""")
        assertEquals("", imageOnly.text)
        assertNotNull(imageOnly.rawJson)

        val other = read("""[{"type":"text","text":"before"},{"type":"resource","uri":"file:///a"}]""")
        assertTrue(other.structured)
        assertTrue(other.text, other.text.startsWith("before\n{"))
        assertTrue(other.text, other.text.contains("\"uri\": \"file:///a\""))
    }

    @Test
    fun emptyAndMissingResultsHaveNothingToShow() {
        assertNull(readToolResult(null))
        assertNull(readToolResult(JsonNull))
        assertEquals("", read("[]").text)
        assertEquals("", read("""[{"type":"text","text":""}]""").text)
    }

    @Test
    fun runawayNestingStopsInsteadOfRecursingForever() {
        var wrapped = "bottom"
        // Each layer doubles the escaping, so twelve is already past the guard without being huge.
        repeat(12) { wrapped = buildJsonArray { add(textBlock(wrapped)) }.toString() }
        val reading = readToolResult(JsonPrimitive(wrapped))!!
        // Unwrapped as far as the guard allows, and still a readable string rather than a crash.
        assertTrue(reading.text.isNotEmpty())
        assertTrue(reading.text, reading.text != "bottom")
    }

    @Test
    fun unwrapToolTextPeelsOnlyExactWrappers() {
        val inner = buildJsonArray { add(textBlock("peeled")) }.toString()
        assertEquals("peeled", unwrapToolText(inner))
        // Whitespace around the wrapper is not part of the answer.
        assertEquals("peeled", unwrapToolText("  $inner\n"))
        assertEquals("Error: boom", unwrapToolText("Error: boom"))
        assertEquals("""{"a":1}""", unwrapToolText("""{"a":1}"""))
        assertEquals("", unwrapToolText(""))
    }
}
