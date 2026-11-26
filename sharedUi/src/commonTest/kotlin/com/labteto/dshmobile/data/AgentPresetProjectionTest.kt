package com.labteto.dshmobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rows keep their last known preset until the control stream or a list read supplies a newer one.
 * A missing baseline key must not behave like JSON null, which intentionally clears the chip, and
 * an answer that reflects an older log position must not undo a live update.
 */
class AgentPresetProjectionTest {
    @Test fun `cached high watermarks cannot undo a lower live preset after migration`() {
        val live = AgentPresetState(-1, "minimal")
        assertEquals(live, listedAgentPreset(live, 900, null, JsonPrimitive("standard"), cached = true))
        val cached = listedAgentPreset(null, 900, null, JsonPrimitive("standard"), cached = true)
        assertEquals("standard", cached?.preset)
        assertEquals(live, newerAgentPreset(cached, live))
    }

    @Test
    fun `a string is a preset`() {
        assertEquals(AgentPresetState(4, "reviewer"), agentPresetUpdate(4, JsonPrimitive("reviewer")))
    }

    @Test
    fun `JSON null clears the preset`() {
        assertEquals(AgentPresetState(4, null), agentPresetUpdate(4, JsonNull))
    }

    @Test
    fun `an absent key is no news`() {
        assertNull(agentPresetUpdate(4, null))
    }

    @Test
    fun `non-string payloads are no news`() {
        val payloads = listOf(
            JsonObject(mapOf("name" to JsonPrimitive("reviewer"))),
            JsonArray(listOf(JsonPrimitive("reviewer"))),
            JsonPrimitive(42),
            JsonPrimitive(true),
        )
        payloads.forEach { value -> assertNull(agentPresetUpdate(4, value)) }
    }

    @Test
    fun `the first state seen is kept`() {
        val incoming = AgentPresetState(2, "standard")
        assertEquals(incoming, newerAgentPreset(null, incoming))
    }

    @Test
    fun `a later position replaces the held preset`() {
        val held = AgentPresetState(2, "standard")
        val incoming = AgentPresetState(5, "minimal")
        assertEquals(incoming, newerAgentPreset(held, incoming))
    }

    @Test
    fun `a list answer from before a live selection does not undo it`() {
        // The control frame for the selection at seq 5 lands first; the list read that was built at
        // seq 3 arrives after it and still says "standard".
        val live = AgentPresetState(5, "minimal")
        val staleList = AgentPresetState(3, "standard")
        assertEquals(live, newerAgentPreset(live, staleList))
    }

    @Test
    fun `the same position goes to the incoming state`() {
        val held = AgentPresetState(5, "minimal")
        val incoming = AgentPresetState(5, null)
        assertEquals(incoming, newerAgentPreset(held, incoming))
    }

    @Test
    fun `a list row reads its preset from the projection`() {
        assertEquals(
            AgentPresetState(3, "standard"),
            listedAgentPreset(held = null, asOfSeq = 3, topLevel = null, projected = JsonPrimitive("standard")),
        )
    }

    @Test
    fun `a list row whose projection omits the preset keeps what the stream delivered`() {
        val live = AgentPresetState(5, "minimal")
        assertEquals(live, listedAgentPreset(held = live, asOfSeq = 9, topLevel = null, projected = null))
        assertEquals(
            live,
            listedAgentPreset(held = live, asOfSeq = 9, topLevel = null, projected = JsonObject(emptyMap())),
        )
        assertNull(listedAgentPreset(held = null, asOfSeq = 9, topLevel = null, projected = null))
    }

    @Test
    fun `a later list row with JSON null clears the preset`() {
        val live = AgentPresetState(5, "minimal")
        assertEquals(
            AgentPresetState(9, null),
            listedAgentPreset(held = live, asOfSeq = 9, topLevel = null, projected = JsonNull),
        )
    }

    @Test
    fun `a top-level preset on the list row wins over the projection`() {
        assertEquals(
            AgentPresetState(3, "standard"),
            listedAgentPreset(held = null, asOfSeq = 3, topLevel = "standard", projected = JsonPrimitive("minimal")),
        )
    }
}
