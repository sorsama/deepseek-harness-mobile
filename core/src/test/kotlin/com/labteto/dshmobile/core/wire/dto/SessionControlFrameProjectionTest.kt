package com.labteto.dshmobile.core.wire.dto

import com.labteto.dshmobile.core.wire.WireJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `session/control` projection frame changed shape in harness 0.2.2.
 *
 * Through 0.2.1 the host broadcast one frame per projection key, `{type, sessionId, key, value,
 * seq}`. 0.2.2 folds projections per committed event and names every key that event touched in a
 * single `changes: [{key, val}]` array. A client that only understood the old shape did not fail
 * loudly — every live projection frame failed the decode and was dropped, so model selection,
 * permissions, todos and the inbox all silently stopped refreshing. These tests pin the
 * normalization that keeps the store's apply path shape-agnostic.
 */
class SessionControlFrameProjectionTest {

    private fun decode(json: String): SessionControlFrame =
        WireJson.decodeFromString(SessionControlFrame.serializer(), json)

    @Test
    fun `a 0-2-2 frame carries every changed key in one normalized changes list`() {
        val frame = decode(
            """{"type":"projection","sessionId":"s","seq":3,"changes":[
                 {"key":"modelSelection","val":{"lastUsed":null,"next":{"provider":"p","model":"m"}}},
                 {"key":"permissionPreset","val":{"current":"read-only"}}]}""",
        ) as SessionControlFrame.Projection

        assertEquals("s", frame.sessionId)
        assertEquals(3, frame.seq)
        assertEquals(
            listOf("modelSelection", "permissionPreset"),
            frame.changes.map { it.key },
        )
        // `val` is the wire name for the change's value; the JSON under it is carried verbatim.
        val next = (frame.changes.first().value as JsonObject)["next"]!!.jsonObject
        assertEquals("p", next["provider"]!!.jsonPrimitive.content)
        assertEquals("m", next["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pre-0-2-2 frame normalizes its single key into the same changes list`() {
        val value = """{"lastUsed":null,"next":{"provider":"p","model":"m"}}"""
        val legacy = decode(
            """{"type":"projection","sessionId":"s","seq":3,"key":"modelSelection","value":$value}""",
        ) as SessionControlFrame.Projection
        val current = decode(
            """{"type":"projection","sessionId":"s","seq":3,
                "changes":[{"key":"modelSelection","val":$value}]}""",
        ) as SessionControlFrame.Projection

        assertEquals(1, legacy.changes.size)
        // Both generations land on the same normalized form, which is what lets the store apply
        // them with one code path.
        assertEquals(current.changes, legacy.changes)
    }

    @Test
    fun `a frame that changed nothing visible decodes as an empty changes list`() {
        val frame = decode(
            """{"type":"projection","sessionId":"s","seq":4,"changes":[]}""",
        ) as SessionControlFrame.Projection

        assertEquals("s", frame.sessionId)
        assertEquals(4, frame.seq)
        assertTrue("an empty commit must not invent a change", frame.changes.isEmpty())
    }

    @Test
    fun `encoding writes the 0-2-2 shape so a mock host speaks the current wire`() {
        val original: SessionControlFrame = SessionControlFrame.Projection(
            sessionId = "s",
            seq = 3,
            changes = listOf(
                SessionProjectionChange(
                    key = "modelSelection",
                    value = buildJsonObject {
                        put(
                            "next",
                            buildJsonObject {
                                put("provider", "p")
                                put("model", "m")
                            },
                        )
                    },
                ),
            ),
        )

        val wire = WireJson.encodeToString(SessionControlFrame.serializer(), original)
        val json = WireJson.parseToJsonElement(wire).jsonObject

        assertEquals("projection", json["type"]!!.jsonPrimitive.content)
        assertEquals("s", json["sessionId"]!!.jsonPrimitive.content)
        assertEquals(3, json["seq"]!!.jsonPrimitive.content.toInt())
        val changes = json["changes"] as JsonArray
        assertEquals(1, changes.size)
        val change = changes[0].jsonObject
        assertEquals("modelSelection", change["key"]!!.jsonPrimitive.content)
        assertEquals("p", change["val"]!!.jsonObject["next"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
        // The 0.2.1 top-level pair must not ride along beside the array.
        assertNull(json["key"])
        assertNull(json["value"])
        assertNull(change["value"])

        assertEquals("the new shape has to round-trip unchanged", original, decode(wire))
    }
}
