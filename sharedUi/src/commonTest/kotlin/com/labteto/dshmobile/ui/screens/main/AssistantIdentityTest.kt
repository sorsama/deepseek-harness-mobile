package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.session.AssistantMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AssistantIdentityTest {

    private fun node(
        seq: Long,
        messageId: String? = null,
        turn: Int? = 3,
        step: Int? = 1,
        streaming: Boolean = false,
    ) = AssistantMessageNode(seq = seq, messageId = messageId, turn = turn, step = step, blocks = emptyList(), streaming = streaming)

    @Test
    fun `a reply keeps its identity when it settles and its seq changes`() {
        val whileWriting = node(seq = 41, messageId = null, streaming = true)
        val settled = node(seq = 37, messageId = "m-9", streaming = false)
        assertEquals(assistantStateKey(whileWriting), assistantStateKey(settled))
    }

    @Test
    fun `a streaming message keeps its identity while durable events move its minted seq`() {
        val before = node(seq = 41, streaming = true)
        val after = node(seq = 44, streaming = true)
        assertEquals(assistantStateKey(before), assistantStateKey(after))
    }

    @Test
    fun `different steps and turns are different messages`() {
        assertNotEquals(assistantStateKey(node(1, step = 1)), assistantStateKey(node(2, step = 2)))
        assertNotEquals(assistantStateKey(node(1, turn = 1)), assistantStateKey(node(2, turn = 2)))
    }

    @Test
    fun `without a turn and step a streaming message falls back to a constant`() {
        val a = node(seq = 5, turn = null, step = null, streaming = true)
        val b = node(seq = 9, turn = null, step = null, streaming = true)
        assertEquals(assistantStateKey(a), assistantStateKey(b))
    }

    @Test
    fun `without a turn and step a durable message uses its id, then its seq`() {
        assertEquals("m-1", assistantStateKey(node(seq = 5, messageId = "m-1", turn = null, step = null)))
        assertEquals(5L, assistantStateKey(node(seq = 5, messageId = null, turn = null, step = null)))
    }

    @Test
    fun `memory hands out the same state for the same message`() {
        val memory = AssistantMemory()
        val key = assistantStateKey(node(seq = 1))
        val panels = memory.reasoningFor(key)
        panels[0] = true
        assertSame(panels, memory.reasoningFor(key))
        assertEquals(true, memory.reasoningFor(key)[0])
        assertSame(memory.actionsFor(key), memory.actionsFor(key))
    }

    @Test
    fun `memory keeps messages apart`() {
        val memory = AssistantMemory()
        val a = assistantStateKey(node(seq = 1, step = 1))
        val b = assistantStateKey(node(seq = 2, step = 2))
        memory.reasoningFor(a)[0] = true
        assertNotSame(memory.reasoningFor(a), memory.reasoningFor(b))
        assertEquals(null, memory.reasoningFor(b)[0])
    }

    @Test
    fun `an open panel survives the reply settling`() {
        // The scenario of the bug, end to end: open a panel on the streaming message, let it settle
        // under a different seq, and find the panel still open on the durable one.
        val memory = AssistantMemory()
        val streamingKey = assistantStateKey(node(seq = 41, streaming = true))
        memory.reasoningFor(streamingKey)[1] = true
        val settledKey = assistantStateKey(node(seq = 37, messageId = "m-9"))
        assertEquals(true, memory.reasoningFor(settledKey)[1])
    }
}
