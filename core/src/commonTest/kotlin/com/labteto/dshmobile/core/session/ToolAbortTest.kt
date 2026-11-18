package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.wire.WireJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A command the user stopped is "stopped", not "failed". The abort forms are the harness's real ones:
 * error name `AbortError`, codes `ABORTED` and `ABORTED_BEFORE_DISPATCH`, a tool's own `*_ABORTED`
 * (`ASK_ABORTED`, `WEB_ABORTED`, `FS_ABORTED`) and the older `interrupted`.
 */
class ToolAbortTest {
    @Test
    fun everyAbortFormTheHarnessRecordsIsAnAbort() {
        assertTrue(isToolAbortError("ABORTED", "AbortError"))
        assertTrue(isToolAbortError("ABORTED", null))
        assertTrue(isToolAbortError("ABORTED_BEFORE_DISPATCH", "AbortError"))
        assertTrue(isToolAbortError("ABORTED_BEFORE_DISPATCH", null))
        assertTrue(isToolAbortError("ASK_ABORTED", null))
        assertTrue(isToolAbortError("WEB_ABORTED", null))
        assertTrue(isToolAbortError("FS_ABORTED", null))
        assertTrue(isToolAbortError("interrupted", null))
        // The name alone is enough: a tool may throw an AbortError with its own code.
        assertTrue(isToolAbortError("SOME_OTHER_CODE", "AbortError"))
        assertTrue(isToolAbortError(null, "AbortError"))
    }

    @Test
    fun aRealFailureIsNotAnAbort() {
        assertFalse(isToolAbortError(null, null))
        assertFalse(isToolAbortError("SANDBOX_UNAVAILABLE", "SandboxUnavailableError"))
        assertFalse(isToolAbortError("ENOENT", null))
        assertFalse(isToolAbortError("TIMEOUT", "TimeoutError"))
        // A code that merely contains the word is not one of the harness's forms.
        assertFalse(isToolAbortError("ABORTED_BY_PEER_RESET", null))
        assertFalse(isToolAbortError("aborted", null))
    }

    private fun ev(type: String, seq: Long, time: Long, json: String) =
        SessionEventEnvelope(type, seq, time, WireJson.parseToJsonElement(json))

    private fun journal(errorJson: String?, isError: Boolean = true) = listOf(
        ev("turn/start", 1, 1_000, """{"turn":1}"""),
        ev(
            "tool/call", 2, 1_100,
            """{"turn":1,"step":1,"callId":"a","name":"pwsh","arguments":"{\"command\":\"sleep 60\"}"}""",
        ),
        ev(
            "tool/result", 3, 1_900,
            """{"turn":1,"step":1,"message":{"role":"tool","source":{"kind":"tool","callId":"a"},"toolCallId":"a",
                "content":[{"type":"text","text":"Error: tool call aborted"}],"isError":$isError,"id":"r3"}
                ${errorJson?.let { ""","error":$it""" } ?: ""}}""",
        ),
        ev("turn/end", 4, 2_000, """{"turn":1,"reason":{"kind":"aborted"}}"""),
    )

    @Test
    fun aToolTheUserStoppedIsStoppedAndNotCountedAsFailed() {
        val turn = deriveTrajectoryTimeline(journal("""{"name":"AbortError","code":"ABORTED"}""")).turns.single()
        val tool = turn.records.filterIsInstance<ToolRecord>().single()
        assertTrue(tool.isError)
        assertTrue(tool.stopped)
        assertFalse(tool.failed)
        assertEquals(0, turn.failedToolCalls)
        assertEquals(1, turn.stoppedToolCalls)
        assertEquals(1, turn.toolCalls)
    }

    @Test
    fun theSessionTotalsKeepStoppedAndFailedApart() {
        val stopped = journal("""{"name":"AbortError","code":"ABORTED_BEFORE_DISPATCH"}""")
        val failedOnTurnTwo = listOf(
            ev("turn/start", 10, 3_000, """{"turn":2}"""),
            ev("tool/call", 11, 3_100, """{"turn":2,"step":1,"callId":"b","name":"pwsh","arguments":"{}"}"""),
            ev(
                "tool/result", 12, 3_200,
                """{"turn":2,"step":1,"message":{"role":"tool","source":{"kind":"tool","callId":"b"},"toolCallId":"b",
                    "content":[{"type":"text","text":"boom"}],"isError":true,"id":"r12"},
                    "error":{"name":"SandboxUnavailableError","code":"SANDBOX_UNAVAILABLE"}}""",
            ),
            ev("turn/end", 13, 3_300, """{"turn":2,"reason":{"kind":"completed"}}"""),
        )
        val totals = deriveTrajectoryTimeline(stopped + failedOnTurnTwo).totals
        assertEquals(2, totals.toolCalls)
        assertEquals(1, totals.failedToolCalls)
        assertEquals(1, totals.stoppedToolCalls)
    }

    @Test
    fun aFailureWithAnotherCodeStaysFailed() {
        val turn = deriveTrajectoryTimeline(journal("""{"name":"Error","code":"ENOENT"}""")).turns.single()
        val tool = turn.records.filterIsInstance<ToolRecord>().single()
        assertFalse(tool.stopped)
        assertTrue(tool.failed)
        assertEquals(1, turn.failedToolCalls)
        assertEquals(0, turn.stoppedToolCalls)
    }

    @Test
    fun aSuccessfulToolIsNeitherFailedNorStopped() {
        val turn = deriveTrajectoryTimeline(journal(null, isError = false)).turns.single()
        val tool = turn.records.filterIsInstance<ToolRecord>().single()
        assertIs<ToolRecord>(tool)
        assertFalse(tool.failed)
        assertFalse(tool.stopped)
        assertEquals(0, turn.failedToolCalls)
        assertEquals(0, turn.stoppedToolCalls)
    }
}
