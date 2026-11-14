package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

class ProcessFoldTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long, text: String = "hello") =
        UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", text)), "user")
    private fun answer(seq: Long, text: String = "done") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", text)))
    private fun reasoning(seq: Long, text: String = "thinking it over") =
        AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("reasoning", text)))

    /** Matches the transcript: turn starts never reach the row list, only the fold's boundary list. */
    private fun fold(nodes: List<ChatNode>, turnStarts: List<Long>, running: Boolean = false) =
        foldProcessRows(groupToolRows(nodes), turnStarts, running)

    @Test fun `the work between a prompt and its answer folds into one row`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            turnStarts = listOf(1),
        )
        assertEquals(3, rows.size)
        assertTrue(rows[0] is TranscriptRow.Message)
        val process = rows[1] as TranscriptRow.Process
        assertEquals(listOf(3L, 4L), process.rows.map { it.seq })
        assertEquals(2, process.steps)
        assertEquals(5L, rows[2].seq)
    }

    @Test fun `the prompt stays outside the fold`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            turnStarts = listOf(1),
        )
        val first = rows[0] as TranscriptRow.Message
        assertEquals(2L, first.seq)
        assertTrue(first.node is UserMessageNode)
    }

    @Test fun `a turn that answered straight away is left alone`() {
        val rows = fold(listOf(user(2), answer(3)), turnStarts = listOf(1))
        assertEquals(2, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `a running turn keeps its steps on screen`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5)),
            turnStarts = listOf(1),
            running = true,
        )
        assertEquals(4, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `injected context keeps a row of its own`() {
        val skill = UserMessageNode(3, "s", listOf(ChatBlock("text", "skill body")), "skill-invocation")
        val rows = fold(
            listOf(user(2), skill, reasoning(4), call(5), answer(6)),
            turnStarts = listOf(1),
        )
        assertEquals(4, rows.size)
        assertTrue(rows[1] is TranscriptRow.Context)
        assertEquals(listOf(4L, 5L), (rows[2] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(6L, rows[3].seq)
    }

    @Test fun `a steering message stays on screen and splits the fold`() {
        // Steering lands inside the running turn: no turn start of its own.
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), user(5, "use the other file"), reasoning(6), call(7), answer(8)),
            turnStarts = listOf(1),
        )
        assertEquals(listOf(2L, 3L, 5L, 6L, 8L), rows.map { it.seq })
        assertEquals(listOf(3L, 4L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertTrue((rows[2] as TranscriptRow.Message).node is UserMessageNode)
        assertEquals(listOf(6L, 7L), (rows[3] as TranscriptRow.Process).rows.map { it.seq })
        // The history sheet lists the steer as something the reader sent; its jump needs the row.
        assertEquals(2, historyRowIndex(rows, 5L))
    }

    @Test fun `a command the reader ran mid-turn stays on screen and splits the fold`() {
        // Picking a permission while a turn runs lands inside that turn. It is the reader's own
        // action, told as its own line; folded away it would sit among the agent's steps.
        val permission = CommandNode(
            5, "command/run",
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"commandId":"c","name":"permission","args":" read-only","source":{"kind":"user"}}""",
            ),
        )
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), permission, reasoning(6), call(7), answer(8)),
            turnStarts = listOf(1),
        )
        assertEquals(listOf(2L, 3L, 5L, 6L, 8L), rows.map { it.seq })
        assertTrue((rows[2] as TranscriptRow.Message).node is CommandNode)
        assertEquals(listOf(3L, 4L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(listOf(6L, 7L), (rows[3] as TranscriptRow.Process).rows.map { it.seq })
    }

    @Test fun `the step count sums every tool call in the fold`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), call(5), answer(6)),
            turnStarts = listOf(1),
        )
        assertEquals(3, (rows[1] as TranscriptRow.Process).steps)
    }

    @Test fun `narration on the way to the answer folds with the steps`() {
        val rows = fold(
            listOf(user(2), answer(3, "let me read that file"), call(4), answer(5)),
            turnStarts = listOf(1),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf(3L, 4L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(5L, rows[2].seq)
    }

    @Test fun `a single step stays a row of its own`() {
        val rows = fold(listOf(user(2), call(3), answer(4)), turnStarts = listOf(1))
        assertEquals(3, rows.size)
        assertTrue(rows[1] is TranscriptRow.Tools)
    }

    @Test fun `a turn whose reply never settled is left alone`() {
        val rows = fold(
            listOf(user(2), reasoning(3), call(4), answer(5, "")),
            turnStarts = listOf(1),
        )
        assertEquals(4, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `each turn folds on its own`() {
        val rows = fold(
            listOf(
                user(2), reasoning(3), call(4), answer(5),
                user(7), reasoning(8), call(9), answer(10),
            ),
            turnStarts = listOf(1, 6),
        )
        assertEquals(6, rows.size)
        assertEquals(listOf(3L, 4L), (rows[1] as TranscriptRow.Process).rows.map { it.seq })
        assertEquals(listOf(8L, 9L), (rows[4] as TranscriptRow.Process).rows.map { it.seq })
    }

    @Test fun `rows before the first turn start are untouched`() {
        val rows = fold(listOf(user(1), reasoning(2), answer(3)), turnStarts = listOf(9))
        assertEquals(3, rows.size)
        assertTrue(rows.none { it is TranscriptRow.Process })
    }

    @Test fun `no turn starts means nothing to fold against`() {
        val rows = fold(listOf(user(1), reasoning(2), call(3), answer(4)), turnStarts = emptyList())
        assertEquals(4, rows.size)
    }
}
