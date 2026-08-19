package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.wire.decodeFromString
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameParserTest {
    @Test
    fun `production envelope conversion preserves assistant block types`() {
        val raw = """{"type":"assistant/message","seq":349,"time":1,"data":{"turn":5,"step":1,"message":{"role":"assistant","content":[{"type":"reasoning","text":"thinking"},{"type":"text","text":"visible reply"}],"source":{"kind":"model","provider":"qwen-local","model":"qwen3.8-27b"},"id":"answer-1"},"usage":{"inputTokens":10,"outputTokens":2}}}"""
        val event = decodeFromString<SessionEvent>(raw)

        val envelope = sessionEventToEnvelope(event)
        val assistant = EventFold("s1").fold(listOf(envelope)).nodes.single() as AssistantMessageNode

        assertEquals(listOf("reasoning", "text"), assistant.blocks.map { it.kind })
        assertEquals("visible reply", assistant.plainText)
    }
}
