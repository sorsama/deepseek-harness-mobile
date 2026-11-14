package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.session.UserMessageNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A node that draws nothing must not reach the transcript's list.
 *
 * The list spaces its rows with `Arrangement.spacedBy`, which happily puts 4dp between two
 * zero-height items — so a turn's worth of bookkeeping events stacks into a visible white band
 * above the conversation. These pin the predicate that keeps them out.
 */
class ChatNodeVisibilityTest {
    private fun streamingTool() = AssistantMessageNode(
        seq = 7, messageId = null, turn = 1, step = 1, streaming = true,
        blocks = listOf(ChatBlock(kind = "tool-call", toolCallId = "c", toolName = "read",
            argumentsJson = "{\"path\":\"src/ทดสอบ")),
    )

    @Test fun `tool-only streaming node reaches the transcript before execution`() {
        val node = streamingTool()
        assertEquals(listOf(node), renderableChatNodes(listOf(node), running = true))
        assertTrue(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test fun `durable tool card replaces the inline preview even if streaming tail remains`() {
        val node = streamingTool()
        val call = ToolCallNode(seq = 6, callId = "c", name = "read", arguments = "{}", turn = 1, step = 1)
        assertEquals(listOf(call), renderableChatNodes(listOf(call, node), running = true))
        assertFalse(node.showsToolPreview(node.blocks.single(), true, setOf("c")))
    }

    @Test fun `non-streaming tool-only messages stay hidden during a running turn`() {
        val node = streamingTool().copy(streaming = false)
        assertTrue(renderableChatNodes(listOf(node), running = true).isEmpty())
        assertFalse(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test fun `stopping the turn hides a provisional preview without changing nodes`() {
        val nodes = listOf(streamingTool())
        assertEquals(nodes, renderableChatNodes(nodes, running = true))
        assertTrue(renderableChatNodes(nodes, running = false).isEmpty())
    }

    @Test fun `another call does not hide this tools partial argument preview`() {
        val node = streamingTool()
        val other = ToolCallNode(seq = 6, callId = "other", name = "read", arguments = "{}", turn = 1, step = 1)
        assertEquals(listOf(other, node), renderableChatNodes(listOf(other, node), running = true))
        val preview = toolRowModel(node.blocks.single().toolName!!, node.blocks.single().argumentsJson, null)
        assertEquals("Read", preview.title)
        assertEquals("src/ทดสอบ", preview.summary)
    }

    @Test fun `interrupted messages keep the stopped marker but no live tool preview`() {
        val node = streamingTool().copy(interrupted = true)
        assertEquals(listOf(node), renderableChatNodes(listOf(node), running = true))
        assertFalse(node.showsToolPreview(node.blocks.single(), true, emptySet()))
    }

    @Test
    fun `structural nodes draw nothing`() {
        assertFalse(TurnStartNode(seq = 1, turn = 1).rendersContent())
        assertFalse(
            ToolResultNode(seq = 2, callId = "c", content = null, isError = false, turn = 1, step = 1)
                .rendersContent(),
        )
        assertFalse(TurnEndNode(seq = 3, turn = 1, reasonKind = "completed").rendersContent())
    }

    @Test
    fun `an unclean turn ending is worth a row`() {
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "interrupted").rendersContent())
        assertTrue(TurnEndNode(seq = 3, turn = 1, reasonKind = "error").rendersContent())
    }

    @Test
    fun `bookkeeping event types are filtered, unknown ones survive`() {
        // The compatibility contract keeps genuinely unknown types visible; the noise is named.
        assertFalse(OtherNode(seq = 4, type = "step/start", data = JsonNull).rendersContent())
        assertFalse(OtherNode(seq = 5, type = "assistant/chunk", data = JsonNull).rendersContent())
        assertTrue(OtherNode(seq = 6, type = "something/new", data = JsonNull).rendersContent())
    }

    // Issue #96: `plugin:translator/request` and `/result` showed up as raw rows after a reasoning
    // translation. The harness writes every plugin record `ignorable`, which means a reader that
    // does not know the type may skip it.
    @Test
    fun `harness plugin records are not transcript rows`() {
        val request = OtherNode(seq = 4, type = "plugin:translator/request", data = JsonNull, ignorable = true)
        val result = OtherNode(seq = 5, type = "plugin:translator/result", data = JsonNull, ignorable = true)
        assertFalse(request.rendersContent())
        assertFalse(result.rendersContent())

        val reply = AssistantMessageNode(
            seq = 6, messageId = "a", turn = 1, step = 1, blocks = listOf(ChatBlock("text", "done")),
        )
        assertEquals(listOf(reply), renderableChatNodes(listOf(request, result, reply), running = false))
    }

    @Test
    fun `an unknown event the harness did not mark skippable still renders`() {
        // Required-but-unrecognised events stay on screen: that is the compatibility contract.
        assertTrue(OtherNode(seq = 4, type = "something/new", data = JsonNull).rendersContent())
        assertTrue(OtherNode(seq = 5, type = "something/new", data = JsonNull, ignorable = false).rendersContent())
        // The plugin namespace alone is not the signal; the harness's marker is.
        assertTrue(OtherNode(seq = 6, type = "plugin:translator/request", data = JsonNull).rendersContent())
    }

    @Test
    fun `a marker cannot hide a type this build draws`() {
        assertTrue(OtherNode(seq = 4, type = "workspace/changes", data = JsonNull, ignorable = true).rendersContent())
        assertTrue(OtherNode(seq = 5, type = "deliverables/presented", data = JsonNull, ignorable = true).rendersContent())
    }

    @Test
    fun `a skipped plugin record stays in the journal and in the folded nodes Trajectory reads`() {
        val events = listOf(
            SessionEventEnvelope("turn/start", 0, 0, buildJsonObject { put("turn", 1) }),
            SessionEventEnvelope("plugin:translator/request", 1, 1, buildJsonObject { put("provider", "deepseek-account") }, ignorable = true),
            SessionEventEnvelope("plugin:translator/result", 2, 2, buildJsonObject { put("requestSeq", 1) }, ignorable = true),
        )
        val snapshot = EventFold("s1").fold(events)

        // Chat draws nothing for them...
        assertTrue(renderableChatNodes(snapshot.nodes, running = false).isEmpty())
        // ...while Trajectory, which re-folds the journal and lists every event raw, still has both.
        val refolded = EventFold("s1").fold(snapshot.journal.map { it.copy(surfaceOp = null, surfaceIntent = null) })
        assertEquals(
            listOf("plugin:translator/request", "plugin:translator/result"),
            refolded.nodes.filterIsInstance<OtherNode>().map { it.type },
        )
        assertEquals(listOf("plugin:translator/request", "plugin:translator/result"), snapshot.journal.drop(1).map { it.type })
    }

    @Test
    fun `an assistant message of nothing but tool references draws nothing`() {
        val node = AssistantMessageNode(
            seq = 7,
            messageId = null,
            turn = 1,
            step = 1,
            blocks = listOf(
                ChatBlock(kind = "tool-call", toolCallId = "c", toolName = "bash"),
                ChatBlock(kind = "text", text = "   "),
            ),
        )
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = node.blocks + ChatBlock("text", "done")).rendersContent())
        assertTrue(node.copy(interrupted = true).rendersContent())
    }

    @Test
    fun `an empty user message draws nothing`() {
        val node = UserMessageNode(seq = 8, messageId = null, blocks = emptyList(), sourceKind = "user")
        assertFalse(node.rendersContent())
        assertTrue(node.copy(blocks = listOf(ChatBlock("text", "hello"))).rendersContent())
    }

    /**
     * A harness that labels a block something this client has not seen should still put the user's
     * words on screen. The predicate and the renderer share [displayText] so they cannot disagree
     * about it — a node judged renderable that then draws nothing costs a gap, and one judged empty
     * that holds text loses the message.
     */
    @Test
    fun `an unknown block carrying text still counts as a user message`() {
        val node = UserMessageNode(
            seq = 9,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = "typed this")),
            sourceKind = "user",
        )
        assertTrue(node.rendersContent())
        assertEquals("typed this", node.displayText())
    }

    @Test
    fun `an unknown block with no text is still nothing to draw`() {
        val node = UserMessageNode(
            seq = 10,
            messageId = null,
            blocks = listOf(ChatBlock("unknown", text = null)),
            sourceKind = "user",
        )
        assertFalse(node.rendersContent())
        assertEquals("", node.displayText())
    }

    @Test
    fun `multiple text blocks join in order`() {
        val node = UserMessageNode(
            seq = 11,
            messageId = null,
            blocks = listOf(ChatBlock("text", "one"), ChatBlock("text", "two")),
            sourceKind = "user",
        )
        assertEquals("one\ntwo", node.displayText())
    }

    @Test
    fun `a turn exposes request context alongside tool calls`() {
        val nodes = listOf(
            TurnStartNode(seq = 1, turn = 1),
            OtherNode(seq = 2, type = "step/start", data = JsonNull),
            OtherNode(seq = 3, type = "request/header", data = JsonNull),
            OtherNode(seq = 4, type = "request/context", data = JsonNull),
            ToolCallNode(seq = 5, callId = "c1", name = "bash", arguments = "{}", turn = 1, step = 1),
            ToolResultNode(seq = 6, callId = "c1", content = null, isError = false, turn = 1, step = 1),
            OtherNode(seq = 7, type = "step/end", data = JsonNull),
            TurnEndNode(seq = 8, turn = 1, reasonKind = "completed"),
        )
        assertEquals(listOf(3L, 4L, 5L), nodes.filter { it.rendersContent() }.map { it.seq })
    }

    /** An empty reasoning block is spacing once settled, but the Thinking row while it streams. */
    @Test fun `blank reasoning renders only while its message is live`() {
        val live = AssistantMessageNode(seq = 9, messageId = null, turn = 1, step = 1, streaming = true,
            blocks = listOf(ChatBlock(kind = "reasoning", text = "")))
        assertTrue(live.rendersContent(running = true))
        assertFalse(live.rendersContent(running = false))
        assertFalse(live.copy(streaming = false).rendersContent(running = true))
        assertTrue(live.copy(streaming = false, blocks = listOf(ChatBlock("reasoning", "plan"))).rendersContent())
    }

    /** A rewind withdraws its range from the transcript; its marker stays, drawn as the divider. */
    @Test fun `a rewind cut hides what it withdrew and keeps its marker as the divider`() {
        fun user(seq: Long, kind: String = "user") = UserMessageNode(seq, "m$seq", listOf(ChatBlock("text", "text $seq")), kind)
        val run = CommandNode(40, "command/run", Json.parseToJsonElement("""{"commandId":"c","name":"rewind","args":"@20 chat"}"""))
        val done = CommandNode(42, "command/done", Json.parseToJsonElement("""{"commandId":"c","kind":"success","sourceEventSeq":41}"""))
        val marker = user(41, kind = "dsh-rewind")
        val rows = renderableChatNodes(listOf(user(10), user(20), user(30), run, marker, done, user(50)), running = false)
        assertEquals(listOf(10L, 41L, 50L), rows.map { it.seq })
        assertTrue((rows[1] as UserMessageNode).isRewindMarker)
    }
}
