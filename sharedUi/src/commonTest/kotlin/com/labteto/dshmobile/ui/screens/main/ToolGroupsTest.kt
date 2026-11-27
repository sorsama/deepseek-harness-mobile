package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.*
import org.junit.Assert.*
import org.junit.Test

class ToolGroupsTest {
    private fun call(seq: Long) = ToolCallNode(seq, "c$seq", "read", "{}", 1, 1)
    private fun user(seq: Long) = UserMessageNode(seq, "u$seq", listOf(ChatBlock("text", "hello")), "user")
    private fun assistant(seq: Long) = AssistantMessageNode(seq, "a$seq", 1, 1, listOf(ChatBlock("text", "done")))
    @Test fun `adjacent calls group without crossing visible message boundaries`() {
        val rows = groupToolRows(listOf(user(1), call(2), call(3), assistant(4), call(5), user(6)))
        assertEquals(5, rows.size)
        assertEquals(listOf(2L, 3L), (rows[1] as TranscriptRow.Tools).calls.map { it.seq })
        assertEquals(listOf(5L), (rows[3] as TranscriptRow.Tools).calls.map { it.seq })
    }
    @Test fun `injected context groups apart from tools and the rewind marker stays a message`() {
        val skill = UserMessageNode(2, "s", listOf(ChatBlock("text", "skill body")), "skill-invocation")
        val system = OtherNode(3, "system/message", kotlinx.serialization.json.JsonNull)
        val marker = UserMessageNode(5, "r", emptyList(), REWIND_SOURCE_KIND)
        val rows = groupToolRows(listOf(user(1), skill, system, call(4), marker))
        assertEquals(listOf(2L, 3L), (rows[1] as TranscriptRow.Context).nodes.map { it.seq })
        assertEquals(listOf(4L), (rows[2] as TranscriptRow.Tools).calls.map { it.seq })
        assertTrue(rows[3] is TranscriptRow.Message)
    }
    @Test fun `group key remains first call as stream grows`() {
        assertEquals(groupToolRows(listOf(call(1))).first().seq, groupToolRows(listOf(call(1), call(2))).first().seq)
        assertTrue(groupToolRows(emptyList()).isEmpty())
    }
}
