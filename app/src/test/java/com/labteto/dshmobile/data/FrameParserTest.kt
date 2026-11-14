package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChunkRows
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.OtherNode
import com.labteto.dshmobile.core.wire.decodeFromString
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionHistoryRecord
import com.labteto.dshmobile.core.wire.dto.SessionWireEvent
import com.labteto.dshmobile.ui.screens.main.renderableChatNodes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // Issue #96. History pages, follow snapshots and live entries all convert through
    // wireEventToEnvelope, and it used to drop `ignorable`, so the fold never learned that the
    // translator's records were skippable and printed them as raw rows.
    @Test
    fun `history conversion keeps the harness ignorable marker`() {
        val data = buildJsonObject { put("provider", "deepseek-account") }
        val marked = SessionWireEvent("plugin:translator/request", 12, 1, data, ignorable = true)
        val unmarked = SessionWireEvent("mystery/event", 13, 2, data)

        assertEquals(true, wireEventToEnvelope(marked).ignorable)
        assertNull(wireEventToEnvelope(unmarked).ignorable)
    }

    @Test
    fun `a translation's plugin records reach the fold marked and draw no transcript row`() {
        val raw = """{"type":"event","event":{"type":"plugin:translator/request","seq":12,"time":1,"data":{"provider":"deepseek-account","text":"hi"},"ignorable":true}}"""
        val record = decodeFromString<SessionHistoryRecord>(raw)

        val envelopes = ChunkRows.expandAll(listOf(record)).map { wireEventToEnvelope(it) }
        val snapshot = EventFold("s1").fold(envelopes)

        assertTrue((snapshot.nodes.single() as OtherNode).isSkippableUnknown)
        assertTrue(renderableChatNodes(snapshot.nodes, running = false).isEmpty())
        assertEquals(listOf("plugin:translator/request"), snapshot.journal.map { it.type })
    }

    @Test
    fun `an unknown event without the marker still draws after history conversion`() {
        val raw = """{"type":"event","event":{"type":"mystery/event","seq":12,"time":1,"data":{"x":1}}}"""
        val record = decodeFromString<SessionHistoryRecord>(raw)

        val snapshot = EventFold("s1").fold(ChunkRows.expandAll(listOf(record)).map { wireEventToEnvelope(it) })

        assertEquals(1, renderableChatNodes(snapshot.nodes, running = false).size)
    }
}
