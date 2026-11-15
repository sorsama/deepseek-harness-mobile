package com.labteto.dshmobile.core.permission

import com.labteto.dshmobile.core.session.ChatNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.SessionEventEnvelope
import com.labteto.dshmobile.core.session.ToolResultNode
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxRefusalTest {
    private fun event(type: String, seq: Long, json: String) =
        SessionEventEnvelope(type, seq, seq, Json.parseToJsonElement(json) as JsonObject)

    private fun user(seq: Long) = event(
        "user/message", seq,
        """{"id":"u$seq","content":[{"type":"text","text":"run it"}],"source":{"kind":"user"}}""",
    )

    private fun call(seq: Long, id: String) = event(
        "tool/call", seq,
        """{"turn":1,"step":1,"callId":"$id","name":"bash","arguments":"{\"command\":\"uname -a\"}"}""",
    )

    /** A tool result as the harness writes one: the thrown error's `{ name, code }` beside the message. */
    private fun result(seq: Long, id: String, code: String?, text: String = "Error: refused") = event(
        "tool/result", seq,
        """{"turn":1,"step":1,${code?.let { "\"error\":{\"name\":\"SandboxUnavailableError\",\"code\":\"$it\"}," } ?: ""}
           "message":{"id":"m$seq","role":"tool","source":{"kind":"tool","callId":"$id"},"toolCallId":"$id","isError":${code != null || text.startsWith("Error")},
             "content":[{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}]}}""",
    )

    private fun nodes(vararg events: SessionEventEnvelope): List<ChatNode> = EventFold("s").fold(events.toList()).nodes

    @Test fun theFoldKeepsTheErrorCode() {
        val folded = nodes(call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE)).filterIsInstance<ToolResultNode>().single()
        assertEquals(SANDBOX_UNAVAILABLE, folded.errorCode)
        assertNull(nodes(call(1, "c1"), result(2, "c1", null, "ok")).filterIsInstance<ToolResultNode>().single().errorCode)
    }

    @Test fun aRefusedCommandInTheLastRequestShowsTheNotice() {
        val refused = nodes(user(0), call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE))
        assertTrue(sandboxRefusalShown(refused, "workspace-write"))
        // An old session on the new runtime reports its preset as `custom` until it is switched.
        assertTrue(sandboxRefusalShown(refused, "custom"))
        assertTrue(sandboxRefusalShown(refused, null))
    }

    @Test fun onlyTheCodeCountsNotTheMessage() {
        val wordsOnly = nodes(
            user(0), call(1, "c1"),
            result(2, "c1", "TOOL_FAILED", "Error: sandbox mode \"workspace-write\" is requested but no sandbox backend is usable"),
        )
        assertFalse(sandboxRefusalShown(wordsOnly, "workspace-write"))
    }

    @Test fun onTheBuiltInHarnessAFailedShellCallWithoutTheCodeCounts() {
        // Harness 0.2.2-rc.1 runs foreground bash as a job and re-throws a start failure without
        // its code; on a host with no sandbox at all the failed shell call is still the refusal.
        val codeLost = nodes(user(0), call(1, "c1"), result(2, "c1", null, "Error: sandbox mode \"workspace-write\" is requested"))
        assertTrue(sandboxRefusalShown(codeLost, "workspace-write", hostWithoutSandbox = true))
        assertTrue(sandboxRefusalShown(codeLost, "custom", hostWithoutSandbox = true))
        // Elsewhere only the code counts.
        assertFalse(sandboxRefusalShown(codeLost, "workspace-write", hostWithoutSandbox = false))
        // A command that ran and failed on a preset that runs commands is just a failed command.
        assertFalse(sandboxRefusalShown(codeLost, "danger-full-access", hostWithoutSandbox = true))
        // A failed file tool is not a refused command.
        val write = event("tool/call", 1, """{"turn":1,"step":1,"callId":"w1","name":"write","arguments":"{}"}""")
        assertFalse(sandboxRefusalShown(nodes(user(0), write, result(2, "w1", null, "Error: no such file")), "workspace-write", hostWithoutSandbox = true))
    }

    @Test fun switchingTheSessionOrSendingAgainClearsIt() {
        val refused = arrayOf(user(0), call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE))
        assertFalse(sandboxRefusalShown(nodes(*refused), "danger-full-access"))
        assertFalse(sandboxRefusalShown(nodes(*refused), "ask-commands"))
        assertFalse(sandboxRefusalShown(nodes(*refused, user(3)), "workspace-write"))
    }

    @Test fun theSubagentDenialOfTheRuntimeGateIsRecognised() {
        // permission.mjs denied(): "<tool>: <why>, so this session cannot ask ..."
        val text = "Error: bash: this is a subagent, and the session that started it asks before commands, " +
            "so this session cannot ask the user for approval and the call was not run. Say so in your reply instead of retrying."
        val denial = nodes(call(1, "c1"), result(2, "c1", null, text)).filterIsInstance<ToolResultNode>().single()
        assertTrue(isSubagentCommandDenial(denial))
        val other = nodes(call(1, "c1"), result(2, "c1", null, "Error: the user rejected tool \"bash\"")).filterIsInstance<ToolResultNode>().single()
        assertFalse(isSubagentCommandDenial(other))
    }
}
