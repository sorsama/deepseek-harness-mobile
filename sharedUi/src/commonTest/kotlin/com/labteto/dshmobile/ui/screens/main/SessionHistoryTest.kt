package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHistoryTest {

    private fun user(seq: Long, text: String, sourceKind: String? = "user") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), sourceKind)

    private fun assistant(seq: Long, text: String, streaming: Boolean = false) =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)), streaming = streaming)

    private fun toolCall(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)

    @Test
    fun `lists only the readers own prompts, newest first`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "first"),
            assistant(3, "answer"),
            user(4, "second"),
            assistant(5, "answer again"),
        )

        val entries = historyEntries(nodes, running = false)

        assertEquals(listOf(4L, 2L), entries.map { it.seq })
    }

    @Test
    fun `every assistant step of a turn is left out`() {
        // A turn is recorded as many assistant nodes, one per step. Listing them is what buried the
        // prompts the reader was looking for, so the count here has to stay at one.
        val nodes: List<ChatNode> = buildList {
            add(TurnStartNode(1, 1))
            add(user(2, "port it"))
            (3L..12L).forEach { add(assistant(it, "step $it")) }
            add(user(13, "and the phone?"))
            (14L..40L).forEach { add(assistant(it, "step $it")) }
        }

        assertEquals(listOf(13L, 2L), historyEntries(nodes, running = false).map { it.seq })
    }

    @Test
    fun `skips turns start nodes and tool calls`() {
        val nodes: List<ChatNode> = listOf(
            TurnStartNode(1, 1),
            user(2, "run it"),
            toolCall(3),
            assistant(4, "done"),
        )

        assertEquals(listOf(2L), historyEntries(nodes, running = false).map { it.seq })
    }

    @Test
    fun `injected context is not listed as something the reader said`() {
        // A skill body or a system note arrives as a UserMessageNode too; it is the harness talking,
        // and listing it as a prompt would misattribute it.
        val nodes: List<ChatNode> = listOf(
            user(1, "the real message"),
            user(2, "injected skill body", sourceKind = "skill"),
        )

        val entries = historyEntries(nodes, running = false)

        assertEquals(listOf(1L), entries.map { it.seq })
        assertFalse(entries.single().hasImage)
    }

    @Test
    fun `a prompt that carried an image is listed even with no text`() {
        val nodes: List<ChatNode> = listOf(
            UserMessageNode(1, "u1", listOf(ChatBlock("image")), "user"),
            UserMessageNode(2, "u2", listOf(ChatBlock("text", "look")), "user"),
        )

        val entries = historyEntries(nodes, running = false)

        assertEquals(listOf(2L, 1L), entries.map { it.seq })
        assertTrue(entries.first { it.seq == 1L }.hasImage)
        assertTrue(entries.first { it.seq == 1L }.text.isEmpty())
        assertFalse(entries.first { it.seq == 2L }.hasImage)
    }

    @Test
    fun `a prompt with neither text nor image is skipped`() {
        val nodes: List<ChatNode> = listOf(
            UserMessageNode(1, "u1", emptyList(), "user"),
            user(2, "   "),
            user(3, "real"),
        )

        assertEquals(listOf(3L), historyEntries(nodes, running = false).map { it.seq })
    }

    @Test
    fun `a running turn does not sneak its partial reply into the list`() {
        val nodes: List<ChatNode> = listOf(
            user(1, "hello"),
            assistant(2, "partial", streaming = true),
        )

        assertEquals(listOf(1L), historyEntries(nodes, running = true).map { it.seq })
    }

    @Test
    fun `timestamps come from the journal map when it has the sequence`() {
        val nodes: List<ChatNode> = listOf(user(7, "hi"), user(8, "again"))

        val entries = historyEntries(nodes, running = false, eventTimes = mapOf(7L to 1_700_000_000_000L))

        assertEquals(1_700_000_000_000L, entries.first { it.seq == 7L }.timeMs)
        assertNull(entries.first { it.seq == 8L }.timeMs)
    }

    @Test
    fun `the text is the same text the transcript draws`() {
        val nodes: List<ChatNode> = listOf(user(1, "  padded  "))

        assertEquals("padded", historyEntries(nodes, running = false).single().text)
    }

    @Test
    fun `a message owns the row its sequence names`() {
        val rows = listOf(
            TranscriptRow.Message(assistant(10, "newest")),
            TranscriptRow.Tools(listOf(toolCall(8), toolCall(9))),
            TranscriptRow.Message(user(7, "older")),
        )

        assertEquals(0, historyRowIndex(rows, 10L))
        assertEquals(2, historyRowIndex(rows, 7L))
        // A tool group's row carries its first call's sequence, so the group is reachable too.
        assertEquals(1, historyRowIndex(rows, 8L))
    }

    @Test
    fun `a sequence no loaded row carries has no index`() {
        val rows = listOf(TranscriptRow.Message(user(7, "only")))

        // A pick can outlive its message: a rewind drops rows while the open sheet still lists them.
        assertNull(historyRowIndex(rows, 3L))
    }

    @Test
    fun `search matches the words in any order and skips unrelated text`() {
        assertTrue(historyMatches("tail fix", "the transcript follow tail fix"))
        assertTrue(historyMatches("", "anything"))
        assertFalse(historyMatches("zzz", "the transcript follow tail fix"))
    }

    @Test
    fun `search wants each word whole, not scattered through the prompt`() {
        // f-i-x is a subsequence of this sentence, so the model sheet's matcher would keep it. On
        // prose that keeps nearly everything, which is a filter that filters nothing.
        val prompt = "the first time I exit the app it forgets the session"

        assertFalse(historyMatches("fix", prompt))
        assertTrue(historyMatches("FORGETS first", prompt))
        assertTrue(historyMatches("  exit  ", prompt))
    }
}
