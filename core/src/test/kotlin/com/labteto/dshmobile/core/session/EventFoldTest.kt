package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.decodeFromString
import com.labteto.dshmobile.core.wire.encodeToJsonElement
import com.labteto.dshmobile.core.wire.dto.SessionEvent
import com.labteto.dshmobile.core.wire.dto.SessionEventSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventFoldTest {

    private fun event(type: String, seq: Long, data: kotlinx.serialization.json.JsonObject): SessionEventEnvelope =
        SessionEventEnvelope(type, seq, seq, data)

    @Test
    fun foldsBasicTurn() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 1) }),
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                putJsonArray("content") {
                    add(buildJsonObject { put("type", "text"); put("text", "hello") })
                }
                putJsonObject("source") { put("kind", "user") }
            }),
            event("assistant/chunk", 2, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") { put("type", "block-start"); put("index", 0); put("blockType", "text") }
            }),
            event("assistant/chunk", 3, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") { put("type", "text-delta"); put("index", 0); put("text", "hi") }
            }),
            event("assistant/message", 4, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("message") { put("id", "a1") }
            }),
            event("turn/end", 5, buildJsonObject {
                put("turn", 1)
                putJsonObject("reason") { put("kind", "completed") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertEquals(4, snapshot.nodes.size)
        assertFalse(snapshot.blank)
        val user = snapshot.nodes[1] as UserMessageNode
        assertEquals("hello", user.previewText)
        val assistant = snapshot.nodes.first { it is AssistantMessageNode } as AssistantMessageNode
        assertEquals("hi", assistant.blocks.first().text)
        assertEquals("hi", assistant.plainText)
        assertFalse(snapshot.running)
        assertEquals(5L, snapshot.lastSeq)
    }

    @Test
    fun interruptedTurnMarksAssistant() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 2) }),
            event("assistant/message", 1, buildJsonObject {
                put("turn", 2); put("step", 1)
                putJsonObject("message") {
                    put("id", "a2")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "partial") })
                    }
                }
            }),
            event("turn/end", 2, buildJsonObject {
                put("turn", 2)
                putJsonObject("reason") { put("kind", "aborted") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistant = snapshot.nodes.first { it is AssistantMessageNode } as AssistantMessageNode
        assertTrue(assistant.interrupted)
    }

    @Test
    fun interruptedMarkerOnTheMessageIsHonoured() {
        // Harness 0.1.0-rc.8 finalises a cancelled turn's delivered prefix as a real message and
        // marks it, so the text survives and the badge is right before the turn has even ended.
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 2) }),
            event("assistant/message", 1, buildJsonObject {
                put("turn", 2); put("step", 1); put("interrupted", true)
                putJsonObject("message") {
                    put("id", "a2")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "as far as I got") })
                    }
                }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistant = snapshot.nodes.first { it is AssistantMessageNode } as AssistantMessageNode
        assertTrue(assistant.interrupted)
        assertEquals("as far as I got", assistant.plainText)
    }

    @Test
    fun theMarkedMessageIsNotRemarkedByTheTurnEnding() {
        // Both sources agree here; the point is that the fallback stands aside rather than
        // walking the turn again and possibly landing on a different, complete message.
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 3) }),
            event("assistant/message", 1, buildJsonObject {
                put("turn", 3); put("step", 1)
                putJsonObject("message") {
                    put("id", "done")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "first step") })
                    }
                }
            }),
            event("assistant/message", 2, buildJsonObject {
                put("turn", 3); put("step", 2); put("interrupted", true)
                putJsonObject("message") {
                    put("id", "cut")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "second step, cut") })
                    }
                }
            }),
            event("turn/end", 3, buildJsonObject {
                put("turn", 3)
                putJsonObject("reason") { put("kind", "interrupted") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistants = snapshot.nodes.filterIsInstance<AssistantMessageNode>()
        assertEquals(2, assistants.size)
        assertFalse(assistants.first().interrupted)
        assertTrue(assistants.last().interrupted)
    }

    @Test
    fun unknownEventBecomesOtherNode() {
        val events = listOf(
            event("mystery/event", 0, buildJsonObject { put("x", 1) }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertTrue(snapshot.nodes.single() is OtherNode)
    }

    @Test
    fun incrementalSkipsDuplicates() {
        val fold = EventFold.Incremental(ConversationSnapshot("s1", lastSeq = 2), "s1")
        val first = fold.apply(event("turn/start", 3, buildJsonObject { put("turn", 7) }))
        val duplicate = fold.apply(event("turn/start", 3, buildJsonObject { put("turn", 7) }))
        assertTrue(first!!.nodes.any { it is TurnStartNode })
        assertEquals(null, duplicate)
    }

    @Test
    fun detectsGap() {
        val events = listOf(
            event("turn/start", 0, buildJsonObject { put("turn", 1) }),
            event("turn/end", 5, buildJsonObject {
                put("turn", 1)
                putJsonObject("reason") { put("kind", "completed") }
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        assertTrue(snapshot.gap)
    }

    @Test
    fun parsesReasoningAndToolBlocks() {
        val message = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "reasoning"); put("text", "thinking…") })
                add(buildJsonObject { put("type", "tool-call"); put("id", "c1"); put("name", "bash"); put("arguments", "{\"command\":\"ls\"}") })
                add(buildJsonObject { put("type", "tool-result"); put("toolCallId", "c1"); put("isError", false) })
            }
        }
        val events = listOf(
            event("assistant/message", 0, buildJsonObject {
                put("turn", 1); put("step", 1)
                put("message", message)
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val assistant = snapshot.nodes.single() as AssistantMessageNode
        assertEquals(3, assistant.blocks.size)
        assertEquals("reasoning", assistant.blocks[0].kind)
        assertEquals("c1", assistant.blocks[1].toolCallId)
        assertEquals("bash", assistant.blocks[1].toolName)
    }

    /**
     * A build that sends `content` as a bare string instead of a block array used to fold to no
     * blocks at all — the user's own message would disappear from the transcript rather than render
     * imperfectly, which is the opposite of the fold's leniency contract everywhere else.
     */
    @Test
    fun foldsStringUserContentIntoATextBlock() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                put("content", "just a string")
            }),
        )
        val snapshot = EventFold("s1").fold(events)
        val user = snapshot.nodes.single() as UserMessageNode
        assertEquals(1, user.blocks.size)
        assertEquals("text", user.blocks[0].kind)
        assertEquals("just a string", user.blocks[0].text)
        assertEquals("just a string", user.previewText)
    }

    @Test
    fun ignoresBlankStringUserContent() {
        val events = listOf(
            event("user/message", 1, buildJsonObject {
                put("id", "m1")
                put("content", "   ")
            }),
        )
        val user = EventFold("s1").fold(events).nodes.single() as UserMessageNode
        assertTrue(user.blocks.isEmpty())
    }

    /**
     * The Android data layer decodes a typed event and then re-encodes its `data` for the fold.
     * Concrete content-block serializers do not add their sealed-class discriminator themselves;
     * losing it here turns both blocks into empty `unknown` rows and makes a completed reply vanish.
     */
    @Test
    fun typedAssistantMessageKeepsBlockTypesWhenConvertedForTheFold() {
        val raw = """{"type":"assistant/message","seq":349,"time":1,"data":{"turn":5,"step":1,"message":{"role":"assistant","content":[{"type":"reasoning","text":"thinking"},{"type":"text","text":"visible reply"}],"source":{"kind":"model","provider":"qwen-local","model":"qwen3.8-27b"},"id":"answer-1"},"usage":{"inputTokens":10,"outputTokens":2}}}"""
        val typed = decodeFromString<SessionEvent>(raw)
        val json = encodeToJsonElement(SessionEventSerializer, typed).jsonObject
        val envelope = SessionEventEnvelope(
            type = typed.type,
            seq = typed.seq.toLong(),
            time = typed.time,
            data = json.getValue("data"),
        )

        val assistant = EventFold("s1").fold(listOf(envelope)).nodes.single() as AssistantMessageNode
        assertEquals(listOf("reasoning", "text"), assistant.blocks.map { it.kind })
        assertEquals("visible reply", assistant.plainText)
    }

    @Test
    fun typedStreamChunkKeepsItsChunkAndCompletedBlockTypes() {
        val raw = """{"type":"assistant/chunk","seq":12,"time":1,"data":{"turn":1,"step":1,"chunk":{"type":"block-end","index":0,"block":{"type":"text","text":"streamed reply"}}}}"""
        val typed = decodeFromString<SessionEvent>(raw)
        val json = encodeToJsonElement(SessionEventSerializer, typed).jsonObject
        val data = json.getValue("data").jsonObject
        val chunk = data.getValue("chunk").jsonObject

        assertEquals("block-end", chunk.getValue("type").jsonPrimitive.content)
        assertEquals("text", chunk.getValue("block").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun metadataChunksDoNotHideANonStreamedAssistantMessage() {
        val events = listOf(
            event("assistant/chunk", 10, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") {
                    put("type", "usage")
                    putJsonObject("usage") { put("inputTokens", 10); put("outputTokens", 2) }
                }
            }),
            event("assistant/chunk", 11, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("chunk") {
                    put("type", "finish")
                    putJsonObject("reason") { put("kind", "completed") }
                }
            }),
            event("assistant/message", 12, buildJsonObject {
                put("turn", 1); put("step", 1)
                putJsonObject("message") {
                    put("id", "answer-1")
                    putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", "visible reply") })
                    }
                }
            }),
        )

        val assistant = EventFold("s1").fold(events).nodes.single() as AssistantMessageNode
        assertEquals(listOf("text"), assistant.blocks.map { it.kind })
        assertEquals("visible reply", assistant.plainText)
    }

    @Test
    fun roundTripsThroughWireJson() {
        // The wire JSON parser (lenient) must accept the event envelope.
        val raw = """{"type":"turn/end","seq":4,"time":5,"data":{"turn":1,"reason":{"kind":"completed"}},"extra":"ignored"}"""
        val parsed = WireJson.parseToJsonElement(raw)
        assertTrue(parsed.toString().contains("turn/end"))
        assertTrue(parsed.toString().contains("completed"))
    }
}
