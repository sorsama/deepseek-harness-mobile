package com.labteto.dshmobile.ui.screens.main

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failure text a tool card shows.
 *
 * This is a regression guard with a specific history: the first version of the extractor read only
 * the *outer* content array of a `tool/result` body, saw a part whose `type` was `tool-result`,
 * skipped it as non-prose, and returned null — so every failure fell back to "Something went
 * wrong" while the real message sat two levels down. The payload below is copied verbatim from a
 * captured session record (`session.v3.jsonl.zstd`), not invented, because the nesting is the whole
 * bug.
 */
class ToolFailureMessageTest {

    private fun parse(json: String) = Json.parseToJsonElement(json)

    @Test
    fun `reads the text out of the nested tool-result content array`() {
        // Shape as the harness actually sends it: content[0] is a tool-result part carrying its
        // own content[0] text part.
        val content = parse(
            """
            [
              {
                "type": "tool-result",
                "toolCallId": "MIbddDg4Own2Mz1wG7gQqX7AyRgdiTcq",
                "content": [
                  {
                    "type": "text",
                    "text": "Error: edit requires reading \"/home/gwang/.dsh/profiles/web/cordis.patch.yml\" first — read the file, then retry"
                  }
                ],
                "isError": true
              }
            ]
            """.trimIndent(),
        )

        assertEquals(
            "Error: edit requires reading \"/home/gwang/.dsh/profiles/web/cordis.patch.yml\" first — read the file, then retry",
            toolFailureMessage(content),
        )
    }

    @Test
    fun `skips leading non-text parts and takes the first prose one`() {
        val content = parse(
            """
            [
              { "type": "tool-result", "content": [
                  { "type": "image", "data": "AAAA" },
                  { "type": "text", "text": "command exited 1: no such file" }
              ] }
            ]
            """.trimIndent(),
        )
        assertEquals("command exited 1: no such file", toolFailureMessage(content))
    }

    @Test
    fun `accepts a text part at the top level too`() {
        val content = parse("""[ { "type": "text", "text": "boom" } ]""")
        assertEquals("boom", toolFailureMessage(content))
    }

    @Test
    fun `accepts a bare string body`() {
        assertEquals("boom", toolFailureMessage(parse("\"boom\"")))
    }

    @Test
    fun `reads a flat error object`() {
        val content = parse("""{ "type": "tool-result", "error": "permission denied" }""")
        assertEquals("permission denied", toolFailureMessage(content))
    }

    @Test
    fun `returns null when there is no prose to show`() {
        // The caller falls back to the generic string, which is the right outcome only when the
        // result genuinely carries nothing readable — not, as before, for every failure.
        assertNull(toolFailureMessage(null))
        assertNull(toolFailureMessage(parse("[]")))
        assertNull(toolFailureMessage(parse("""[ { "type": "text", "text": "   " } ]""")))
        assertNull(toolFailureMessage(parse("""[ { "type": "image", "data": "AAAA" } ]""")))
    }

    @Test
    fun `unwraps a failure whose text is itself a block list`() {
        val inner = """[{\"type\":\"text\",\"text\":\"Error: build failed\"}]"""
        val content = parse("""[ { "type": "text", "text": "$inner" } ]""")
        assertEquals("Error: build failed", toolFailureMessage(content))
        // Text that only looks like JSON stays as the tool wrote it.
        assertEquals("[INFO] failed", toolFailureMessage(parse("""[ { "type": "text", "text": "[INFO] failed" } ]""")))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val content = parse("""[ { "type": "tool-result", "content": [ { "type": "text", "text": "  boom\n\n" } ] } ]""")
        assertEquals("boom", toolFailureMessage(content))
    }
}
