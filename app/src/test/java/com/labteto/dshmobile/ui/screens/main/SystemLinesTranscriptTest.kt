package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.CompactionNode
import com.labteto.dshmobile.core.session.PlanModeNode
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.TurnErrorNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a reader's command leaves in the transcript's row list. These stay with the list filter
 * (`renderableChatNodes`, ChatNodeVisibility.kt) until it moves to sharedUi with the transcript; the
 * line itself is tested in sharedUi's SystemLinesTest, where the payloads below are described.
 */
class SystemLinesTranscriptTest {
    private fun run(seq: Long, id: String, name: String, args: String? = null, source: String? = """{"kind":"user"}""") =
        CommandNode(
            seq, "command/run",
            Json.parseToJsonElement(
                buildString {
                    append("""{"commandId":"$id","name":"$name"""")
                    if (args != null) append(""","args":"$args"""")
                    if (source != null) append(""","source":$source""")
                    append("}")
                },
            ),
        )

    private fun done(seq: Long, id: String, kind: String = "success", text: String? = null) =
        CommandNode(
            seq, "command/done",
            Json.parseToJsonElement(
                buildString {
                    append("""{"commandId":"$id","kind":"$kind"""")
                    if (text != null) append(""","text":"$text"""")
                    append("}")
                },
            ),
        )

    // ---- what reaches the transcript -------------------------------------------------------

    @Test fun `the done that settles a run is folded into the run's line`() {
        val nodes = listOf(run(10, "c1", "permission", " read-only"), done(11, "c1", text = "preset read-only"))
        assertEquals(setOf(11L), foldedCommandDones(nodes))
        assertEquals(listOf(10L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `every pair folds on its own id`() {
        val nodes = listOf(
            run(10, "a", "permission", " read-only"), done(11, "a"),
            run(20, "b", "permission", " workspace-write"), done(21, "b"),
        )
        assertEquals(listOf(10L, 20L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `a done whose run is on an earlier page says nothing when it went through`() {
        val nodes = listOf(done(11, "gone"))
        assertEquals(setOf(11L), foldedCommandDones(nodes))
        assertTrue(renderableChatNodes(nodes, running = false).isEmpty())
    }

    @Test fun `a done whose run is on an earlier page keeps its row when it failed`() {
        val nodes = listOf(done(11, "gone", kind = "error", text = "nope"))
        assertTrue(foldedCommandDones(nodes).isEmpty())
        assertEquals(listOf(11L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `a run that is not the reader's keeps both of its rows`() {
        val nodes = listOf(run(10, "c1", "xyz", source = """{"kind":"plugin"}"""), done(11, "c1"))
        assertTrue(foldedCommandDones(nodes).isEmpty())
        assertEquals(listOf(10L, 11L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test fun `the agent's rows draw exactly as before around a reader's command`() {
        val user = UserMessageNode(1, "m1", listOf(ChatBlock("text", "hello")), "user")
        val call = ToolCallNode(seq = 2, callId = "c", name = "read", arguments = "{}", turn = 1, step = 1)
        val reply = AssistantMessageNode(3, "a3", 1, 1, listOf(ChatBlock("text", "done")))
        val failure = TurnErrorNode(4, "no key", "AUTH")
        val plan = PlanModeNode(7, active = true)
        val compaction = CompactionNode(8, "compaction/summary", Json.parseToJsonElement("{}"))
        val nodes = listOf(
            user, call, reply, failure,
            run(5, "p", "permission", " read-only"), done(6, "p"),
            plan, compaction,
        )
        // Only the command's own done is gone; every row the agent or the harness drew is still there.
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 7L, 8L), renderableChatNodes(nodes, running = false).map { it.seq })
    }
}
