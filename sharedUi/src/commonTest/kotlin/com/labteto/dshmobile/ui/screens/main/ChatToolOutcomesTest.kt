package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.DisclosureState
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.SubagentPhase
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.hasArguments
import com.labteto.dshmobile.ui.components.hasBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a call ends, as the transcript words it: stopped, closed, failed. The payloads are the real
 * ones. The stopped and dismissed shapes were copied from raw call data on a device run against the
 * harness (0.2.1-rc.2); the codes behind them are in the harness source:
 * `core/tools/src/index.ts` (`toolAbortedResult`, `TOOL_ABORTED`), `core/agent-loop/src/tool-calls.ts`
 * (`appendSkippedToolCall`), `interaction/user-questions/src/index.ts` (`ASK_ABORTED`,
 * `ASK_TIMED_OUT`) and `client/ui-user-questions` (`ASK_CANCELLED`).
 */
class ChatToolOutcomesTest {

    private fun call(name: String, args: String = "{}", id: String = "c1") = ToolCallNode(10, id, name, args, 1, 1)

    private fun text(value: String): JsonElement = JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", value) }))

    private fun result(
        text: String,
        isError: Boolean = true,
        code: String? = null,
        name: String? = null,
        id: String = "c1",
    ) = ToolResultNode(20, id, text(text), isError, 1, 1, null, code, name, null)

    // ---- Stopped ---------------------------------------------------------------------------

    // The pwsh call stopped with the Stop button: isError, "Error: tool call aborted", { AbortError, ABORTED }.
    private val stoppedRunning get() = result("Error: tool call aborted", code = "ABORTED", name = "AbortError")

    // A call the stop reached before it started, or one the scheduler skipped.
    private val stoppedBefore get() = result("Error: tool call aborted before dispatch", code = "ABORTED_BEFORE_DISPATCH", name = "AbortError")

    @Test
    fun `both abort results of the harness read as stopped`() {
        assertEquals(ToolFailure.Interrupted, toolFailureOf(stoppedRunning))
        assertEquals(ToolFailure.Interrupted, toolFailureOf(stoppedBefore))
        assertTrue(isAbortedResult(stoppedRunning))
        assertTrue(isAbortedResult(stoppedBefore))
    }

    @Test
    fun `a tool's own abort code and the older interrupted code read as stopped too`() {
        // By the harness's convention a tool's abort code ends in _ABORTED.
        assertTrue(isAbortedResult(result("Error: read aborted", code = "FS_ABORTED", name = "FsError")))
        assertTrue(isAbortedResult(result("Error: Exa search aborted", code = "WEB_ABORTED", name = "WebError")))
        assertTrue(isAbortedResult(result("Error: x", code = "interrupted")))
        // The error name alone is enough: every canonical abort carries AbortError.
        assertTrue(isAbortedResult(result("Error: tool call aborted", code = null, name = "AbortError")))
    }

    @Test
    fun `an ordinary failure is not stopped`() {
        // The failed shell call of the device run: no code at all.
        val spawn = result("Error: spawn C:\\Program Files\\nodejs\\node.exe ENOENT")
        assertFalse(isAbortedResult(spawn))
        assertEquals(ToolFailure.Failed("spawn C:\\Program Files\\nodejs\\node.exe ENOENT"), toolFailureOf(spawn))
        assertFalse(isAbortedResult(result("ok", isError = false, code = "ABORTED")))
        assertNull(toolFailureOf(result("ok", isError = false)))
    }

    @Test
    fun `a stopped call is not counted as failed in its group`() {
        val pwsh = call("pwsh", """{"command":"Start-Sleep -Seconds 600","description":"Wait for the build to finish"}""")
        assertFalse(callFailed(pwsh, stoppedRunning))
        assertFalse(callFailed(pwsh, stoppedBefore))
        assertTrue(callFailed(pwsh, result("Error: spawn pwsh ENOENT")))
        assertFalse(callFailed(pwsh, null))
        assertFalse(callFailed(pwsh, result("ok", isError = false)))
    }

    @Test
    fun `a permission denial still counts as failed`() {
        val denied = result("Error: denied", code = "APPROVAL_REJECTED_BY_USER", name = "DshMobilePermissionDenied")
        assertEquals(ToolFailure.Denied(PermissionDenial.Rejected, null), toolFailureOf(denied))
        assertTrue(callFailed(call("bash"), denied))
    }

    // ---- One outcome for every dot ---------------------------------------------------------

    private val pwshArgs = """{"command":"Start-Sleep -Seconds 600","description":"Wait for the build to finish"}"""

    @Test
    fun `every abort payload of the harness is stopped for every reader of the outcome`() {
        val pwsh = call("pwsh", pwshArgs)
        val aborts = listOf(
            stoppedRunning,
            stoppedBefore,
            result("Error: read aborted", code = "FS_ABORTED", name = "FsError"),
            result("Error: Exa search aborted", code = "WEB_ABORTED", name = "WebError"),
            result("Error: x", code = "interrupted"),
        )
        for (aborted in aborts) {
            // Whether or not the session still says it is working: a result is a result.
            assertEquals(ToolOutcome.Stopped, toolOutcome(pwsh.name, aborted, running = true))
            assertEquals(ToolOutcome.Stopped, toolOutcome(pwsh.name, aborted, running = false))
            // The row's dot is amber, and the group's header does not count it as failed.
            assertEquals(DisclosureState.Stopped, toolOutcome(pwsh.name, aborted, running = false).disclosureState())
            assertFalse(callFailed(pwsh, aborted))
        }
    }

    @Test
    fun `a failure is failed for every reader of the outcome`() {
        val pwsh = call("pwsh", pwshArgs)
        val spawn = result("Error: spawn C:\\Program Files\\nodejs\\node.exe ENOENT")
        val denied = result("Error: denied", code = "APPROVAL_REJECTED_BY_USER", name = "DshMobilePermissionDenied")
        for (failed in listOf(spawn, denied)) {
            assertEquals(ToolOutcome.Failed, toolOutcome(pwsh.name, failed, running = false))
            assertEquals(DisclosureState.Error, toolOutcome(pwsh.name, failed, running = false).disclosureState())
            assertTrue(callFailed(pwsh, failed))
        }
    }

    @Test
    fun `a call with no result is running while the session works and plain after`() {
        assertEquals(ToolOutcome.Running, toolOutcome("pwsh", null, running = true))
        assertEquals(DisclosureState.Running, toolOutcome("pwsh", null, running = true).disclosureState())
        assertEquals(ToolOutcome.Done, toolOutcome("pwsh", null, running = false))
        assertEquals(DisclosureState.Idle, toolOutcome("pwsh", null, running = false).disclosureState())
        assertEquals(ToolOutcome.Done, toolOutcome("pwsh", result("ok", isError = false), running = true))
        // An error flag that is not set is not an error, whatever code is recorded.
        assertEquals(ToolOutcome.Done, toolOutcome("pwsh", result("ok", isError = false, code = "ABORTED"), running = false))
    }

    @Test
    fun `a closed or timed out question is done and a stopped one is stopped`() {
        assertEquals(ToolOutcome.Done, toolOutcome("ask_user_question", dismissed(), running = false))
        assertEquals(
            ToolOutcome.Done,
            toolOutcome("ask_user_question", result("Error: the user cancelled", code = "ASK_CANCELLED", name = "UserQuestionError"), running = false),
        )
        assertEquals(
            ToolOutcome.Done,
            toolOutcome("ask_user_question", result("Error: timed out", code = "ASK_TIMED_OUT", name = "UserQuestionError"), running = false),
        )
        assertEquals(
            ToolOutcome.Stopped,
            toolOutcome("ask_user_question", result("Error: aborted", code = "ASK_ABORTED", name = "UserQuestionError"), running = false),
        )
        // The same code on another tool is nothing a reader did to a question.
        assertEquals(ToolOutcome.Failed, toolOutcome("edit", dismissed(), running = false))
    }

    @Test
    fun `a stopped command's card is stopped not failed so its own dot is not red`() {
        val stopped = buildToolCardView(call("pwsh", pwshArgs), stoppedRunning, running = false) as ToolCardView.TerminalCard
        assertTrue(stopped.stopped)
        assertFalse(stopped.failed)
        // Like a failed one it keeps the command, since there is no output to say what ran.
        assertTrue(stopped.cutShort)
        assertEquals("Start-Sleep -Seconds 600", stopped.title)

        val failed = buildToolCardView(call("pwsh", pwshArgs), result("Error: spawn pwsh ENOENT"), running = false) as ToolCardView.TerminalCard
        assertTrue(failed.failed)
        assertFalse(failed.stopped)

        val finished = buildToolCardView(call("pwsh", pwshArgs), result("done\n", isError = false), running = false) as ToolCardView.TerminalCard
        assertFalse(finished.failed)
        assertFalse(finished.stopped)
        assertFalse(finished.cutShort)
    }

    @Test
    fun `a stopped background launch is stopped not failed`() {
        val args = """{"command":"npm start","description":"Start the dev server","run_in_background":true}"""
        val stopped = buildToolCardView(call("pwsh", args), stoppedBefore, running = false) as ToolCardView.TerminalCard
        assertTrue(stopped.background)
        assertTrue(stopped.stopped)
        assertFalse(stopped.failed)
        val failed = buildToolCardView(call("pwsh", args), result("Error: run_in_background is disabled"), running = false) as ToolCardView.TerminalCard
        assertTrue(failed.failed)
        assertFalse(failed.stopped)
    }

    @Test
    fun `a stopped subagent call is stopped not failed to start`() {
        val args = """{"description":"Read the docs","prompt":"Read the docs folder"}"""
        val stopped = buildToolCardView(call("subagent", args), stoppedRunning, running = false) as SubagentCard
        assertEquals(SubagentPhase.Stopped, stopped.phase)
        val failed = buildToolCardView(call("subagent", args), result("Error: subagent tool requires a calling agent"), running = false) as SubagentCard
        assertEquals(SubagentPhase.Failed, failed.phase)
    }

    @Test
    fun `nested calls read the same outcome from their settlement record`() {
        fun record(json: String) = WireJson.parseToJsonElement(json) as JsonObject
        assertEquals(
            ToolOutcome.Stopped,
            ptcChildOutcome(record("""{"name":"pwsh","isError":true,"error":{"name":"AbortError","code":"ABORTED"}}""")),
        )
        assertEquals(
            ToolOutcome.Failed,
            ptcChildOutcome(record("""{"name":"read","isError":true,"error":{"name":"FsError","code":"ENOENT"}}""")),
        )
        // An error with no recorded error object is still an error.
        assertEquals(ToolOutcome.Failed, ptcChildOutcome(record("""{"name":"read","isError":true}""")))
        assertEquals(ToolOutcome.Done, ptcChildOutcome(record("""{"name":"read","content":[]}""")))
        // A start record has no error flag: nothing to show yet.
        assertEquals(ToolOutcome.Done, ptcChildOutcome(record("""{"name":"read","arguments":{"path":"a"}}""")))
    }

    // ---- Questions -------------------------------------------------------------------------

    private val questionArgs = """{"questions":[{"id":"goal","question":"What next?","options":[{"label":"Ship"}]}]}"""

    private fun dismissed() = result("Error: the user closed this question request", code = "cancelled", name = "UserQuestionError")

    @Test
    fun `a dismissed question is neither failed nor a failure line`() {
        val dismissed = dismissed()
        val q = buildToolCardView(call("ask_user_question", questionArgs), dismissed, running = false) as com.labteto.dshmobile.ui.components.QuestionCard
        assertEquals(QuestionOutcome.Cancelled, q.outcome)
        assertTrue(q.explainsFailure(dismissed))
        assertFalse(callFailed(call("ask_user_question", questionArgs), dismissed))
        // The question is not shown as stopped either: that is for the turn's stop.
        assertEquals(QuestionOutcome.Cancelled, questionVerdictOf(dismissed))
    }

    @Test
    fun `the three endings of a question each have a verdict that is not a failure`() {
        val web = result("Error: the user cancelled ask_user_question", code = "ASK_CANCELLED", name = "UserQuestionError")
        val stopped = result("Error: ask_user_question was aborted before the user answered", code = "ASK_ABORTED", name = "UserQuestionError")
        val timedOut = result("Error: ask_user_question timed out before the user answered", code = "ASK_TIMED_OUT", name = "UserQuestionError")
        val ask = call("ask_user_question", questionArgs)
        assertEquals(QuestionOutcome.Cancelled, questionVerdictOf(web))
        assertEquals(QuestionOutcome.Interrupted, questionVerdictOf(stopped))
        assertEquals(QuestionOutcome.TimedOut, questionVerdictOf(timedOut))
        listOf(web, stopped, timedOut).forEach { assertFalse(callFailed(ask, it)) }
        // A stopped question is stopped (amber) for the row; the others are plain.
        assertEquals(ToolFailure.Interrupted, toolFailureOf(stopped))
        // A stop that reached the question before it opened has the generic abort form.
        assertEquals(QuestionOutcome.Interrupted, questionVerdictOf(stoppedBefore))
    }

    @Test
    fun `a question the harness refused for another reason is still a failure`() {
        val bad = result("Error: ask_user_question requires at least one question", code = "EMPTY_QUESTIONS", name = "UserQuestionError")
        assertNull(questionVerdictOf(bad))
        assertTrue(callFailed(call("ask_user_question", questionArgs), bad))
        // And a "cancelled" code on some other tool is not a closed question.
        assertTrue(callFailed(call("edit"), dismissed()))
    }

    // ---- The failure line's words ----------------------------------------------------------

    @Test
    fun `the tool's own Error prefix is dropped after the app's label`() {
        assertEquals("unknown tool \"list_subagent_models\"", plainFailureMessage("Error: unknown tool \"list_subagent_models\""))
        assertEquals("boom", plainFailureMessage("TypeError: boom"))
        assertEquals("boom", plainFailureMessage("Error: Error: boom"))
        assertEquals("command exited 1", plainFailureMessage("command exited 1"))
        assertEquals("ENOENT: no such file", plainFailureMessage("ENOENT: no such file"))
        assertNull(plainFailureMessage("Error:"))
        assertNull(plainFailureMessage(null))
    }

    @Test
    fun `a failure message is one line`() {
        assertEquals("invalid arguments", plainFailureMessage("Error: invalid arguments\n  at parse (x.js:1)\n  at run"))
        assertEquals("second", plainFailureMessage("\n\nError: second\nthird"))
    }

    @Test
    fun `a failure message with a Windows path keeps the path whole`() {
        // The real text of a failed edit on a device run.
        val line = "Error: old_string was not found in \"C:\\demo\\user\\docs-site\\README.md\""
        assertEquals("old_string was not found in \"C:\\demo\\user\\docs-site\\README.md\"", plainFailureMessage(line))
    }

    @Test
    fun `a very long failure line is cut with an ellipsis and never mid pair`() {
        val long = "Error: " + "x".repeat(1000)
        val cut = plainFailureMessage(long)!!
        assertEquals(FAILURE_LINE_MAX_CHARS + 1, cut.length)
        assertTrue(cut.endsWith("…"))
        // A surrogate pair straddling the limit is dropped whole, not split: the 240th char here is
        // the first half of the 120th emoji.
        val astral = "a" + "😀".repeat(200)
        assertEquals("a" + "😀".repeat(119) + "…", plainFailureMessage(astral))
    }

    // ---- Output that only repeats the failure ---------------------------------------------

    @Test
    fun `an unknown tool's text is not drawn as both the failure line and the output`() {
        val res = result("Error: unknown tool \"list_subagent_models\"")
        val failure = toolFailureOf(res)
        assertTrue(outputRepeatsFailure(res, failure))
        val generic = buildToolCardView(call("list_subagent_models"), res, running = false)
        assertFalse(showsOutputRow(generic, res, failure))
    }

    @Test
    fun `output with more to say than the failure line is kept`() {
        val res = result("Error: build failed\nsrc/a.kt:3 unresolved reference")
        assertFalse(outputRepeatsFailure(res, toolFailureOf(res)))
        val generic = buildToolCardView(call("build"), res, running = false)
        assertTrue(showsOutputRow(generic, res, toolFailureOf(res)))
    }

    @Test
    fun `the canonical abort text is not repeated under the stopped line`() {
        assertTrue(outputRepeatsFailure(stoppedRunning, toolFailureOf(stoppedRunning)))
        assertTrue(outputRepeatsFailure(stoppedBefore, toolFailureOf(stoppedBefore)))
    }

    @Test
    fun `a refusal's text is kept since the line words it differently`() {
        val denied = result("Error: denied by the user", code = "APPROVAL_REJECTED_BY_USER", name = "DshMobilePermissionDenied")
        assertFalse(outputRepeatsFailure(denied, toolFailureOf(denied)))
        assertFalse(outputRepeatsFailure(null, ToolFailure.Interrupted))
        assertFalse(outputRepeatsFailure(denied, null))
    }

    // ---- A tool with no arguments ----------------------------------------------------------

    @Test
    fun `no arguments means no arguments element`() {
        assertFalse(hasArguments(null))
        assertFalse(hasArguments(""))
        assertFalse(hasArguments("   "))
        assertFalse(hasArguments("{}"))
        assertFalse(hasArguments(" { } "))
        assertTrue(hasArguments("""{"limit":5}"""))
        // Not an object: shown as given rather than hidden.
        assertTrue(hasArguments("[1]"))
    }

    @Test
    fun `a generic card with nothing of its own has no body to draw`() {
        // list_agents takes no arguments: the card used to draw an empty bordered box.
        val bare = ToolCardView.GenericCard(title = "list_agents", rawInput = "{}")
        assertFalse(bare.hasBody())
        assertTrue(ToolCardView.GenericCard(title = "x", rawInput = """{"a":1}""").hasBody())
        assertTrue(ToolCardView.GenericCard(title = "x", rawInput = "{}", locations = listOf("a")).hasBody())
        assertTrue(ToolCardView.GenericCard(title = "x", rawInput = "{}", content = listOf(ContentBlockView.TextBlock("t"))).hasBody())
    }

    @Test
    fun `a tool with no arguments still opens when it returned text`() {
        val res = result("agent-1 running\nagent-2 idle", isError = false)
        val card = buildToolCardView(call("list_agents"), res, running = false)
        assertTrue(card is ToolCardView.GenericCard)
        assertFalse(card.hasBody())
        // The row opens for the Output row that is drawn outside the card.
        assertTrue(showsOutputRow(card, res, toolFailureOf(res)))
        // Nothing to show, nothing to open.
        assertFalse(showsOutputRow(card, result("   ", isError = false), null))
        assertFalse(showsOutputRow(card, null, null))
    }

    // ---- One stopped pill per stopped turn --------------------------------------------------

    private fun assistant(seq: Long, turn: Int, interrupted: Boolean, blocks: List<ChatBlock>) =
        AssistantMessageNode(seq = seq, messageId = null, turn = turn, step = 1, blocks = blocks, interrupted = interrupted)

    @Test
    fun `a stopped turn draws its pill once at its end`() {
        val message = assistant(2, 1, interrupted = true, blocks = listOf(ChatBlock("text", "Waiting for the build.")))
        val nodes = listOf(
            TurnStartNode(1, 1), message,
            ToolCallNode(3, "c1", "pwsh", "{}", 1, 1),
            ToolResultNode(4, "c1", null, true, 1, 1, null, "ABORTED", "AbortError", null),
            TurnEndNode(5, 1, "aborted"),
        )
        val stopped = turnsStoppedByTurnEnd(nodes)
        assertEquals(setOf(1), stopped)
        assertFalse(message.showsStoppedPill(stopped))
        // The message itself is still drawn: it has words.
        assertTrue(message.rendersContent(stoppedTurns = stopped))
        // The turn end is the one that says it.
        assertTrue(nodes.last().rendersContent(stoppedTurns = stopped))
    }

    @Test
    fun `a cut short message with no words and a stopped turn is not an empty row`() {
        val empty = assistant(2, 1, interrupted = true, blocks = emptyList())
        val nodes = listOf(TurnStartNode(1, 1), empty, TurnEndNode(3, 1, "interrupted"))
        assertEquals(listOf(3L), renderableChatNodes(nodes, running = false).map { it.seq })
    }

    @Test
    fun `a cut short message keeps its pill when nothing else says the turn stopped`() {
        val message = assistant(2, 1, interrupted = true, blocks = listOf(ChatBlock("text", "Half an ans")))
        // The stop has not reached the log yet, or the turn failed rather than stopped.
        assertTrue(message.showsStoppedPill(emptySet()))
        val failed = listOf(TurnStartNode(1, 1), message, TurnEndNode(3, 1, "error"))
        assertTrue(message.showsStoppedPill(turnsStoppedByTurnEnd(failed)))
        // Another turn's stop does not hide this turn's pill.
        assertTrue(message.showsStoppedPill(setOf(2)))
    }
}
