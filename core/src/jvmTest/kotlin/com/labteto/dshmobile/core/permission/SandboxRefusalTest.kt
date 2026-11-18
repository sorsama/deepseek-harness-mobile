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

    /** The sentence the runtime README quotes for the shape without a code (harness 0.2.2-rc.1). */
    private val documentedSentence = "Error: sandbox mode \"workspace-write\" is requested but no sandbox backend is usable on this host; " +
        "refusing to run the command unconfined. Install bubblewrap or run a Landlock-enforcing kernel (Linux), ensure sandbox-exec " +
        "is usable (macOS), or ensure the ACL restricted-token runner can start (Windows) — otherwise switch the consumer to danger-full-access."

    @Test fun onTheBuiltInHarnessTheDocumentedSentenceWithoutTheCodeCounts() {
        // Harness 0.2.2-rc.1 runs foreground bash as a job and re-throws a start failure without
        // its code: the result has no `error`, only the sentence.
        val codeLost = nodes(user(0), call(1, "c1"), result(2, "c1", null, documentedSentence))
        assertTrue(sandboxRefusalShown(codeLost, "workspace-write", hostWithoutSandbox = true))
        assertTrue(sandboxRefusalShown(codeLost, "custom", hostWithoutSandbox = true))
        // Any sandboxed mode: only the words around the mode are compared.
        val readOnly = nodes(user(0), call(1, "c1"), result(2, "c1", null, documentedSentence.replace("workspace-write", "read-only")))
        assertTrue(sandboxRefusalShown(readOnly, "read-only", hostWithoutSandbox = true))
        // Elsewhere only the code counts.
        assertFalse(sandboxRefusalShown(codeLost, "workspace-write", hostWithoutSandbox = false))
        // On a preset that runs commands there is nothing to explain.
        assertFalse(sandboxRefusalShown(codeLost, "danger-full-access", hostWithoutSandbox = true))
        // The same words from a file tool are not a refused command.
        val write = event("tool/call", 1, """{"turn":1,"step":1,"callId":"w1","name":"write","arguments":"{}"}""")
        assertFalse(sandboxRefusalShown(nodes(user(0), write, result(2, "w1", null, documentedSentence)), "workspace-write", hostWithoutSandbox = true))
    }

    @Test fun aShellCallThatFailedForAnotherReasonIsNotTheRefusal() {
        // #111 counted every failed shell call on a sandboxed preset; with the shape documented,
        // a command that failed in its own way is left as a failed command.
        val failed = nodes(user(0), call(1, "c1"), result(2, "c1", null, "Error: bash: exit code 1\nls: cannot access 'nope'"))
        assertFalse(sandboxRefusalShown(failed, "workspace-write", hostWithoutSandbox = true))
        assertFalse(isSandboxRefusalText("Error: bash: exit code 1"))
        assertTrue(isSandboxRefusalText(documentedSentence))
        assertTrue(isSandboxRefusalText(documentedSentence.removePrefix("Error: ")))
    }

    @Test fun theCodedShapeCountsWithTheRunnerFailureAppended() {
        val coded = nodes(user(0), call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE, "$documentedSentence Runner failure: spawn EPERM"))
        assertTrue(sandboxRefusalShown(coded, "workspace-write"))
        assertTrue(sandboxRefusalShown(coded, "workspace-write", hostWithoutSandbox = true))
    }

    @Test fun switchingTheSessionOrSendingAgainClearsIt() {
        val refused = arrayOf(user(0), call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE))
        assertFalse(sandboxRefusalShown(nodes(*refused), "danger-full-access"))
        assertFalse(sandboxRefusalShown(nodes(*refused), "ask-commands"))
        assertFalse(sandboxRefusalShown(nodes(*refused, user(3)), "workspace-write"))
    }

    /** A denial of the runtime's gate, permissions version 2: `error` = `{ name: DshMobilePermissionDenied, code }`. */
    private fun denial(seq: Long, id: String, code: String, text: String = "Error: some sentence for the model") = event(
        "tool/result", seq,
        """{"turn":1,"step":1,"error":{"name":"$PERMISSION_DENIED_ERROR_NAME","code":"$code"},
           "message":{"id":"m$seq","role":"tool","source":{"kind":"tool","callId":"$id"},"toolCallId":"$id","isError":true,
             "content":[{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}]}}""",
    )

    private fun single(vararg events: SessionEventEnvelope) = nodes(*events).filterIsInstance<ToolResultNode>().single()

    @Test fun denialsAreReadFromTheCodeNotTheSentence() {
        assertEquals(PERMISSION_DENIED_ERROR_NAME, single(call(1, "c1"), denial(2, "c1", "APPROVAL_REJECTED_BY_USER")).errorName)
        val cases = mapOf(
            "APPROVAL_NOT_AVAILABLE_TO_SUBAGENT" to PermissionDenial.SubagentCannotAsk,
            "APPROVAL_REJECTED_BY_USER" to PermissionDenial.Rejected,
            "APPROVAL_CANCELLED" to PermissionDenial.Cancelled,
            "APPROVAL_UNAVAILABLE" to PermissionDenial.NobodyToAsk,
            "APPROVAL_TEXT_TOO_LARGE" to PermissionDenial.TextTooLarge,
            "APPROVAL_TEXT_UNREADABLE" to PermissionDenial.Other,
            "PERMISSION_GATE_ERROR" to PermissionDenial.Other,
            "APPROVAL_SWITCHED_OFF" to PermissionDenial.Other,
            "APPROVAL_OWNER_SESSION_GONE" to PermissionDenial.Other,
            "A_CODE_FROM_A_LATER_RUNTIME" to PermissionDenial.Other,
        )
        for ((code, expected) in cases) {
            // The sentence is reworded on purpose: only the code decides.
            assertEquals(code, expected, permissionDenialOf(single(call(1, "c1"), denial(2, "c1", code, "Error: reworded"))))
        }
        // The subagent code wins over a sentence that says something else.
        assertTrue(isSubagentCommandDenial(single(call(1, "c1"), denial(2, "c1", "APPROVAL_NOT_AVAILABLE_TO_SUBAGENT", "Error: nope"))))
    }

    @Test fun aCodeFromSomethingElseIsNotADenial() {
        assertNull(permissionDenialOf(single(call(1, "c1"), result(2, "c1", SANDBOX_UNAVAILABLE))))
        assertNull(permissionDenialOf(single(call(1, "c1"), result(2, "c1", "TOOL_FAILED"))))
        assertNull(permissionDenialOf(single(call(1, "c1"), result(2, "c1", null, "ok"))))
        // Even a version 1 subagent sentence does not count when another code is present.
        val text = "Error: bash: this is a subagent, and the session that started it asks before commands, so ..."
        assertNull(permissionDenialOf(single(call(1, "c1"), result(2, "c1", "TOOL_FAILED", text))))
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

    /**
     * Auto review's refusal as the harness records it: `error` = `{ name: AutoReviewDeniedError,
     * code: AUTO_REVIEW_DENIED, reason }` (the web client reads the same three fields in
     * `ui-tool/src/client/tool/models/tool-call-model.ts`, `deriveAutoReviewDenial`).
     */
    private fun autoReview(seq: Long, id: String, name: String, reason: String?) = event(
        "tool/result", seq,
        """{"turn":1,"step":1,"error":{"name":"$name","code":"$AUTO_REVIEW_DENIED"${reason?.let { ",\"reason\":${Json.encodeToString(String.serializer(), it)}" } ?: ""}},
           "message":{"id":"m$seq","role":"tool","source":{"kind":"tool","callId":"$id"},"toolCallId":"$id","isError":true,
             "content":[{"type":"text","text":"Error: Auto review denied this tool call"}]}}""",
    )

    @Test fun autoReviewRefusalIsReadFromItsNameAndCode() {
        val denied = single(call(1, "c1"), autoReview(2, "c1", AUTO_REVIEW_DENIED_ERROR_NAME, "Deletes files outside the workspace"))
        assertEquals(PermissionDenial.AutoReviewRejected, permissionDenialOf(denied))
        // The fold keeps the reviewer's reason for the transcript to show.
        assertEquals("Deletes files outside the workspace", denied.errorReason)
    }

    @Test fun theAutoReviewCodeUnderAnotherNameIsNotARefusal() {
        // The web client requires both the name and the code; so does this.
        assertNull(permissionDenialOf(single(call(1, "c1"), autoReview(2, "c1", "SomeOtherError", null))))
    }

    @Test fun anAutoReviewRefusalWithoutAReasonStillCounts() {
        val denied = single(call(1, "c1"), autoReview(2, "c1", AUTO_REVIEW_DENIED_ERROR_NAME, null))
        assertEquals(PermissionDenial.AutoReviewRejected, permissionDenialOf(denied))
        assertNull(denied.errorReason)
    }
}
