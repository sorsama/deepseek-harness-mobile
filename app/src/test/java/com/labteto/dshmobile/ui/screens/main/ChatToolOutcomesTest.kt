package com.labteto.dshmobile.ui.screens.main

import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.ChatBlock
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.session.TurnEndNode
import com.labteto.dshmobile.core.session.TurnStartNode
import com.labteto.dshmobile.ui.components.ContentBlockView
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.hasArguments
import com.labteto.dshmobile.ui.components.hasBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
