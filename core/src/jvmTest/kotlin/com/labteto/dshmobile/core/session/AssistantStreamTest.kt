package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact stream harness 0.1.3 embeds in every settlement and hands a reconnecting follower
 * as its opening baseline. Expansion has to agree with the host's `expandAssistantStream` to the
 * chunk, or a reply resumed mid-stream reads differently from one watched from the start.
 */
class AssistantStreamTest {

    @Test
    fun `a text run expands into one delta per fragment with accumulated times`() {
        val chunks = AssistantStream.expand(
            WireJson.parseToJsonElement(
                """[{"type":"text-chunks","time0":1000,"index":0,"dt":[5,-2],"texts":["Hel","lo"," there"]}]""",
            ),
        )
        assertEquals(3, chunks.size)
        // Sequence numbers are the host's business; times accumulate the gaps, and a gap may be
        // negative because the host's wall clock can step backwards.
        assertEquals(listOf(1000L, 1005L, 1003L), chunks.map { it.time })
        assertEquals(
            listOf("Hel", "lo", " there"),
            chunks.map { it.chunk.getValue("text").jsonPrimitive.content },
        )
        assertTrue(chunks.all { it.chunk.getValue("type").jsonPrimitive.content == "text-delta" })
        assertTrue(chunks.all { it.chunk.getValue("index").jsonPrimitive.content == "0" })
    }

    @Test
    fun `reasoning and tool-call runs keep their own delta types`() {
        val chunks = AssistantStream.expand(
            WireJson.parseToJsonElement(
                """[{"type":"reasoning-chunks","time0":1,"index":0,"dt":[],"texts":["hmm"]},
                    {"type":"tool-call-chunks","time0":2,"index":1,"dt":[1],"id":"call-1","name":"bash","args":["{\"c","md\":1}"]}]""",
            ),
        )
        assertEquals(3, chunks.size)
        assertEquals("reasoning-delta", chunks[0].chunk.getValue("type").jsonPrimitive.content)
        assertEquals("tool-call-delta", chunks[1].chunk.getValue("type").jsonPrimitive.content)
        assertEquals("call-1", chunks[1].chunk.getValue("id").jsonPrimitive.content)
        assertEquals("bash", chunks[2].chunk.getValue("name").jsonPrimitive.content)
        assertEquals("md\":1}", chunks[2].chunk.getValue("argumentsDelta").jsonPrimitive.content)
        assertEquals(3L, chunks[2].time)
    }

    @Test
    fun `a tool-call run without a name expands without inventing one`() {
        // The host omits `name` when the members disagreed; a client that defaulted it would
        // label a call the model never named.
        val chunks = AssistantStream.expand(
            WireJson.parseToJsonElement(
                """[{"type":"tool-call-chunks","time0":2,"index":0,"dt":[],"id":"call-1","args":["{}"]}]""",
            ),
        )
        assertFalse(chunks.single().chunk.containsKey("name"))
    }

    @Test
    fun `verbatim chunks pass through in order with the runs`() {
        val chunks = AssistantStream.expand(
            WireJson.parseToJsonElement(
                """[{"type":"chunk","time":10,"chunk":{"type":"block-start","index":0,"blockType":"text"}},
                    {"type":"text-chunks","time0":11,"index":0,"dt":[],"texts":["hi"]},
                    {"type":"chunk","time":12,"chunk":{"type":"block-end","index":0,"block":{"type":"text","text":"hi"}}},
                    {"type":"chunk","time":13,"chunk":{"type":"finish","reason":{"kind":"stop"}}}]""",
            ),
        )
        assertEquals(
            listOf("block-start", "text-delta", "block-end", "finish"),
            chunks.map { it.chunk.getValue("type").jsonPrimitive.content },
        )
        assertEquals(listOf(10L, 11L, 12L, 13L), chunks.map { it.time })
    }

    @Test
    fun `a malformed record drops the whole stream rather than half of it`() {
        // Three fragments, one gap: the record contradicts itself. A partial expansion would put
        // a fragment of an answer on screen with no way to say what is missing.
        val mismatched = AssistantStream.expand(
            WireJson.parseToJsonElement(
                """[{"type":"text-chunks","time0":1,"index":0,"dt":[1],"texts":["a","b","c"]},
                    {"type":"chunk","time":9,"chunk":{"type":"finish","reason":{"kind":"stop"}}}]""",
            ),
        )
        assertTrue(mismatched.isEmpty())

        val unknownRecord = AssistantStream.expand(
            WireJson.parseToJsonElement("""[{"type":"future-chunks","time0":1}]"""),
        )
        assertTrue(unknownRecord.isEmpty())

        val idLess = AssistantStream.expand(
            WireJson.parseToJsonElement("""[{"type":"tool-call-chunks","time0":1,"index":0,"dt":[],"args":["{}"]}]"""),
        )
        assertTrue(idLess.isEmpty())
    }

    @Test
    fun `an absent or empty stream expands to nothing`() {
        assertTrue(AssistantStream.expand(null).isEmpty())
        assertTrue(AssistantStream.expand(WireJson.parseToJsonElement("[]")).isEmpty())
        assertTrue(AssistantStream.expand(WireJson.parseToJsonElement("{}")).isEmpty())
    }
}
