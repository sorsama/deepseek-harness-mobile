package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.GoalEditRequest
import com.labteto.dshmobile.core.wire.dto.GoalRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GoalProjectionReferenceTest {
    private val snapshot = WireJson.parseToJsonElement("""
        {"id":"goal-1","revision":17,"objective":"Old objective","phase":"paused","maxGoalRounds":50}
    """.trimIndent())

    @Test fun `real harness envelope supplies exact current revision to multiline edit`() {
        // packages/goal/goal/src/index.ts goalProjectionSchema: goal plus replay metadata.
        val projection = buildJsonObject {
            put("goal", snapshot)
            put("roundsStarted", 3)
            put("createdAt", 1000)
            put("updatedAt", 2000)
        }
        val ref = goalRefFromProjection(projection)!!
        assertEquals(GoalRef("goal-1", 17), ref)
        val objective = "แก้ไขเป้าหมาย\nบรรทัดที่สอง"
        val request = WireJson.encodeToJsonElement(GoalEditRequest.serializer(),
            GoalEditRequest("session", ref, objective)).jsonObject
        assertEquals("goal-1", request.getValue("ref").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(17, request.getValue("ref").jsonObject.getValue("revision").jsonPrimitive.int)
        assertEquals(objective, request.getValue("objective").jsonPrimitive.content)
    }

    @Test fun `legacy bare snapshot and minimal ref keep their revision`() {
        assertEquals(GoalRef("goal-1", 17), goalRefFromProjection(snapshot))
        assertEquals(GoalRef("legacy", 4), goalRefFromProjection(
            WireJson.parseToJsonElement("""{"id":"legacy","revision":4}""")))
    }

    @Test fun `cleared or absent projection cannot revive an envelope reference`() {
        assertNull(goalRefFromProjection(null))
        assertNull(goalRefFromProjection(JsonNull))
        assertNull(goalRefFromProjection(buildJsonObject {
            put("goal", JsonNull)
            put("id", "stale")
            put("revision", 99)
        }))
    }

    @Test fun `malformed or invalid current references are unavailable`() {
        listOf("{}", "[]", """{"goal":{"id":"x"}}""",
            """{"id":"x","revision":0}""", """{"id":"","revision":1}""")
            .forEach { assertNull(goalRefFromProjection(WireJson.parseToJsonElement(it))) }
    }
}
