package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a held tool call offers to copy, read from its own arguments rather than its row. */
class HoldActionsTest {

    private fun copies(arguments: String?, output: String? = null) =
        toolCopies(arguments, output).map { it.kind to it.text }

    /** The row shows the description; the reason to copy is to paste the command that actually ran. */
    @Test fun `a shell call copies its command, not its description`() {
        assertEquals(
            listOf(ToolCopyKind.Command to "./gradlew :app:test --tests \"*Hold*\""),
            copies("""{"command":"./gradlew :app:test --tests \"*Hold*\"","description":"Run the hold tests"}"""),
        )
    }

    /** The row cuts the path down to the working directory; the copy keeps it whole. */
    @Test fun `a file call copies the full path`() {
        assertEquals(
            listOf(ToolCopyKind.Path to "C:\\work\\todo-api\\src\\app.ts"),
            copies("""{"path":"C:\\work\\todo-api\\src\\app.ts","offset":1}"""),
        )
        assertEquals(listOf(ToolCopyKind.Path to "/srv/a.txt"), copies("""{"file_path":"/srv/a.txt"}"""))
    }

    @Test fun `a fetch copies its link and a search its queries, one per line`() {
        assertEquals(listOf(ToolCopyKind.Link to "https://example.com/a"), copies("""{"url":"https://example.com/a"}"""))
        assertEquals(
            listOf(ToolCopyKind.Query to "kotlin flows\ncompose gestures"),
            copies("""{"queries":["kotlin flows","","compose gestures"]}"""),
        )
        assertEquals(listOf(ToolCopyKind.Query to "TODO\\("), copies("""{"pattern":"TODO\\(","path":""}"""))
    }

    @Test fun `output comes last, and only when there is some`() {
        assertEquals(
            listOf(ToolCopyKind.Command to "ls", ToolCopyKind.Output to "a\nb"),
            copies("""{"command":"ls","description":"List"}""", "a\nb"),
        )
        assertEquals(listOf(ToolCopyKind.Command to "ls"), copies("""{"command":"ls"}""", "  "))
    }

    /** A call still streaming its arguments, or one with a non-object payload, offers what it can. */
    @Test fun `unreadable arguments still offer the output`() {
        assertEquals(listOf(ToolCopyKind.Output to "done"), copies("""{"command":"l""", "done"))
        assertEquals(emptyList<Pair<ToolCopyKind, String>>(), copies("[1,2]"))
        assertEquals(emptyList<Pair<ToolCopyKind, String>>(), copies(null))
        // A non-string value is not something to paste as a command.
        assertEquals(emptyList<Pair<ToolCopyKind, String>>(), copies("""{"command":42}"""))
    }

    @Test fun `the output is every text part of the result, else its failure prose`() {
        fun result(content: String, isError: Boolean = false) =
            ToolResultNode(seq = 2, callId = "c", content = Json.parseToJsonElement(content), isError = isError, turn = 1, step = 1)
        assertEquals(
            "first\nsecond",
            toolOutputText(result("""[{"type":"text","text":"first"},{"type":"image"},{"type":"text","text":"second"}]""")),
        )
        assertEquals(
            "Error: no such file",
            toolOutputText(result("""[{"type":"tool-result","content":[{"type":"text","text":"Error: no such file"}]}]""", isError = true)),
        )
        assertNull(toolOutputText(null))
    }

    /** Copy output is the meaningful text, not a wrapper level of it (issue #97). */
    @Test fun `copy output unwraps envelopes and double-encoded text to the final text`() {
        fun result(content: String) =
            ToolResultNode(seq = 2, callId = "c", content = Json.parseToJsonElement(content), isError = false, turn = 1, step = 1)
        assertEquals("BUILD SUCCESSFUL", toolOutputText(result("""{"content":[{"type":"text","text":"BUILD SUCCESSFUL"}]}""")))
        assertEquals("BUILD SUCCESSFUL", toolOutputText(result("""{"output":"BUILD SUCCESSFUL"}""")))
        assertEquals(
            "BUILD SUCCESSFUL",
            toolOutputText(result("""[{"type":"text","text":"[{\"type\":\"text\",\"text\":\"BUILD SUCCESSFUL\"}]"}]""")),
        )
        // A real structured result copies as readable JSON, not as a one-line blob.
        assertEquals("{\n  \"jobId\": \"j1\",\n  \"state\": \"running\"\n}", toolOutputText(result("""{"jobId":"j1","state":"running"}""")))
    }

    /** Mentions are written the way the composer's own reference picker writes them. */
    @Test fun `a mention is relative to the working directory when it can be`() {
        assertEquals("src\\app.ts", mentionPath("C:\\work\\todo-api\\src\\app.ts", "C:\\work\\todo-api"))
        assertEquals("/etc/hosts", mentionPath("/etc/hosts", "/home/me/project"))
        assertEquals("notes.md", mentionPath("notes.md", null))
    }
}
