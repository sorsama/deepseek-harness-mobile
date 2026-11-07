package com.labteto.dshmobile.core.wire

import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Harness021Test {
    @Test fun `all transport routes remain under a proxy prefix`() {
        for (route in listOf("/", "/api/host", "/api/remote-stream", "/api/file?x=1")) {
            assertEquals("https://example.com/harness/team" + route, resolveHarnessUrl("https://example.com/harness/team/?token=discard#fragment", route).toString())
        }
        assertEquals("http://[::1]:3080/api/host", resolveHarnessUrl("http://[::1]:3080", "/api/host").toString())
        assertThrows(IllegalArgumentException::class.java) { resolveHarnessUrl("https://host/prefix", "/../escape") }
        assertThrows(IllegalArgumentException::class.java) { resolveHarnessUrl("https://host/prefix", "//evil") }
    }
    @Test fun `calendar editing retains explicit zones and ISO weekdays`() {
        val weekly = automationTiming("weekly", "10:15:00", "Asia/Bangkok", "1,7")["weekly"]!!.jsonObject
        assertEquals("Asia/Bangkok", weekly["time_zone"]!!.jsonPrimitive.content)
        assertEquals(listOf(1,7), weekly["weekdays"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertThrows(IllegalArgumentException::class.java) { automationTiming("weekly", "10:00", "UTC", "1,1") }
        assertThrows(IllegalArgumentException::class.java) { automationTiming("every", "59", "", "") }
        assertThrows(Exception::class.java) { automationTiming("at", "2026-10-10T12:00:00", "", "") }
    }
    @Test fun `streamed arguments show complete and partial strings but ignore nested names`() {
        assertEquals("work/a", partialArgumentStrings("""{"path":"work/a""")["path"]!!.jsonPrimitive.content)
        assertEquals("hello\nworld", partialArgumentStrings("""{"description":"hello\nworld","nested":{"path":"bad"},"more":"x""")["description"]!!.jsonPrimitive.content)
        assertFalse(partialArgumentStrings("""{"nested":{"path":"bad"}}""").containsKey("path"))
        assertEquals("abc", partialArgumentStrings("""{"path":"abc\u0""")["path"]!!.jsonPrimitive.content)
    }
    @Test fun `catalog entries decode target bindings and legacy session ids`() {
        val entry = WireJson.decodeFromString(AutomationTask.serializer(), """
            {"id":"t","kind":"rrule","title":"check","prompt":"check",
             "scheduledAt":"2026-01-01T02:00:00.000Z",
             "rrule":"DTSTART;TZID=Asia/Bangkok:20260101T090000\nRRULE:FREQ=HOURLY;INTERVAL=1",
             "target":{"kind":"session","sessionId":"s-1"},"involvedSessionIds":["s-1","s-2"],
             "status":"paused","createdAt":"2026-01-01T00:00:00.000Z","updatedAt":"2026-01-01T00:01:00.000Z"}
        """.trimIndent())
        assertEquals("rrule", entry.kind)
        assertTrue(entry.rrule!!.contains("FREQ=HOURLY"))
        assertEquals("s-1", entry.sessionIdEffective)
        assertEquals(listOf("s-1", "s-2"), entry.involvedSessionIds)
        assertEquals("paused", entry.status)
        assertEquals("2026-01-01T00:01:00.000Z", entry.updatedAt)
        // A `new` target creates a fresh Session per delivery, so it names no delivery Session.
        assertNull(entry.copy(target = buildJsonObject { put("kind", "new") }, sessionId = "").sessionIdEffective)
        // A 0.2.1 catalog entry still decodes: the binding used to be a top-level `sessionId`.
        val legacy = WireJson.decodeFromString(AutomationTask.serializer(),
            """{"id":"t","kind":"at","title":"old","prompt":"old","scheduledAt":"2026-01-01T00:00:00.000Z","sessionId":"s-legacy","status":"inactive"}""")
        assertEquals("s-legacy", legacy.sessionIdEffective)
        assertEquals("inactive", legacy.status)
    }

    @Test fun `schedule edit encodes the update patch and omits absent fields`() {
        val update = WireJson.encodeToJsonElement(AutomationEditRequest.serializer(), AutomationEditRequest(
            id = "t", action = "update",
            patch = AutomationPatch(title = "renamed", timing = automationTiming("daily", "09:00", "UTC", "")),
            expectedUpdatedAt = "u-1",
        )).jsonObject
        assertEquals("update", update["action"]!!.jsonPrimitive.content)
        assertEquals("u-1", update["expectedUpdatedAt"]!!.jsonPrimitive.content)
        val patch = update["patch"]!!.jsonObject
        assertEquals("renamed", patch["title"]!!.jsonPrimitive.content)
        assertEquals("daily", patch["timing"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertFalse(patch.containsKey("prompt")); assertFalse(patch.containsKey("active")); assertFalse(patch.containsKey("target"))
        val delete = WireJson.encodeToJsonElement(AutomationEditRequest.serializer(), AutomationEditRequest("t", "delete")).jsonObject
        assertEquals("delete", delete["action"]!!.jsonPrimitive.content)
        assertFalse(delete.containsKey("patch")); assertFalse(delete.containsKey("expectedUpdatedAt"))
    }

    @Test fun `schedule edit outcomes decode as entry delete miss or conflict`() {
        val entry = WireJson.decodeFromString(AutomationMutation.serializer(),
            """{"id":"t","kind":"rrule","title":"check","prompt":"check","scheduledAt":"2026-01-01T00:00:00Z","status":"active","updatedAt":"u-2"}""")
        assertEquals("t", entry.id); assertNull(entry.code); assertNull(entry.record)
        val deleted = WireJson.decodeFromString(AutomationMutation.serializer(), """{"id":"t","deleted":true}""")
        assertEquals(true, deleted.deleted)
        assertEquals("schedule_not_found",
            WireJson.decodeFromString(AutomationMutation.serializer(), """{"id":"t","code":"schedule_not_found"}""").code)
        assertEquals("schedule_conflict",
            WireJson.decodeFromString(AutomationMutation.serializer(), """{"id":"t","code":"schedule_conflict"}""").code)
    }
    @Test fun `legacy question requests still decode without a wait`() {
        assertNull(WireJson.decodeFromString(AskUserQuestionRequestEvent.serializer(), """{"questions":[]}""").wait)
        assertEquals(QuestionWait("call", true), WireJson.decodeFromString(AskUserQuestionRequestEvent.serializer(), """{"questions":[],"wait":{"callId":"call","timed":true}}""").wait)
    }
}
