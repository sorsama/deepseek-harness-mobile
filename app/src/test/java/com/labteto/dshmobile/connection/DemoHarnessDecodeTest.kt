package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.permission.PermissionDenial
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.CommandNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.core.session.ToolCallNode
import com.labteto.dshmobile.core.session.ToolResultNode
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionPageRequest
import com.labteto.dshmobile.data.wireEventToEnvelope
import com.labteto.dshmobile.mockharness.CiFixtures
import com.labteto.dshmobile.mockharness.DemoHarness
import com.labteto.dshmobile.ui.components.DetailsCard
import com.labteto.dshmobile.ui.components.QuestionCard
import com.labteto.dshmobile.ui.components.QuestionOutcome
import com.labteto.dshmobile.ui.components.SearchMatches
import com.labteto.dshmobile.ui.components.SkillCard
import com.labteto.dshmobile.ui.components.SkillPhase
import com.labteto.dshmobile.ui.components.SubagentCard
import com.labteto.dshmobile.ui.components.TodoCard
import com.labteto.dshmobile.ui.components.ToolCardView
import com.labteto.dshmobile.ui.components.WebCardKind
import com.labteto.dshmobile.ui.screens.main.SystemLine
import com.labteto.dshmobile.ui.screens.main.ToolFailure
import com.labteto.dshmobile.ui.screens.main.buildToolCardView
import com.labteto.dshmobile.ui.screens.main.isRun
import com.labteto.dshmobile.ui.screens.main.systemLineFor
import com.labteto.dshmobile.ui.screens.main.toolFailureOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo harness speaks raw JSON, so this holds it to the client's own DTOs and fold: the list decodes into twelve
 * titled rows, the first session's log folds into thinking, a tool call with its result, and a markdown answer, and the
 * third session's into every tool card the chat draws.
 * If the mock drifts from what the app reads, the iOS scenarios built on it would test nothing.
 */
class DemoHarnessDecodeTest {

    private fun <T> RpcResult<T>.value(): T = when (this) {
        is RpcResult.Ok -> value
        is RpcResult.Err -> error("${error.code}: ${error.message}")
    }

    @Test
    fun `the demo list and transcript decode into what the app shows`() = runBlocking {
        val servers = CiFixtures.start(withDemo = true)
        try {
            val api = DshApiClient(OkHttpRpcTransport(servers.demoUrl!!, OkHttpClient(), 5_000, 5_000))

            val rows = api.sessionList().value().items
            assertEquals(DemoHarness.SESSION_COUNT, rows.size)
            val titles = rows.map { (it.projections?.values?.get("title") as? JsonPrimitive)?.contentOrNull }
            assertTrue("untitled rows in $titles", titles.all { !it.isNullOrBlank() })
            assertEquals(2, rows.mapNotNull { it.cwd }.toSet().size)

            val page = api.sessionPage(SessionPageRequest(SessionAddress.Session(sessionId = "demo-01"), throughSeq = 100)).value()
            val snapshot = EventFold("demo-01").fold(page.records.map { wireEventToEnvelope(it.event) })
            val assistant = snapshot.nodes.filterIsInstance<AssistantMessageNode>()
            assertEquals(3, assistant.size)
            assertEquals(listOf("reasoning", "text", "tool-call"), assistant[0].blocks.map { it.kind })
            assertTrue(assistant[1].plainText.contains("```kotlin"))
            assertEquals(listOf("reasoning", "text"), assistant[2].blocks.map { it.kind })
            assertEquals(DemoHarness.GALLERY, assistant[2].plainText)
            val call = snapshot.nodes.filterIsInstance<ToolCallNode>().single()
            val result = snapshot.nodes.filterIsInstance<ToolResultNode>().single()
            assertEquals("read_file", call.name)
            assertEquals(call.callId, result.callId)
            assertTrue(!snapshot.running)
        } finally {
            servers.stop()
        }
    }

    @Test
    fun `the tool-card session folds into every card the chat draws`() = runBlocking {
        val servers = CiFixtures.start(withDemo = true)
        try {
            val api = DshApiClient(OkHttpRpcTransport(servers.demoUrl!!, OkHttpClient(), 5_000, 5_000))
            val id = DemoHarness.TOOL_CARDS_SESSION
            val page = api.sessionPage(SessionPageRequest(SessionAddress.Session(sessionId = id), throughSeq = 1_000)).value()
            val nodes = EventFold(id).fold(page.records.map { wireEventToEnvelope(it.event) }).nodes
            val results = nodes.filterIsInstance<ToolResultNode>().associateBy { it.callId }
            val calls = nodes.filterIsInstance<ToolCallNode>()
            val cards = calls.map { call -> call.name to buildToolCardView(call, results[call.callId], running = false, cwd = "C:\\Users\\demo\\mobile-app") }
            fun card(name: String, nth: Int = 0) = cards.filter { it.first == name }[nth].second

            assertTrue(card("todo_write") is TodoCard)
            assertTrue((card("grep") as ToolCardView.SearchCard).matches is SearchMatches.FileMatches)
            assertTrue((card("glob") as ToolCardView.SearchCard).matches is SearchMatches.PathList)
            assertEquals(13, (card("read") as ToolCardView.ReadCard).lines.size)
            assertTrue((card("web_search") as ToolCardView.WebCard).kind is WebCardKind.Search)
            assertTrue((card("web_fetch") as ToolCardView.WebCard).kind is WebCardKind.Fetch)
            assertTrue(card("edit") is ToolCardView.DiffCard)
            assertTrue(card("write") is ToolCardView.DiffCard)
            assertEquals(0, (card("bash") as ToolCardView.TerminalCard).exitCode)
            assertEquals(1, (card("pwsh") as ToolCardView.TerminalCard).exitCode)
            assertEquals("child-07", (card("subagent") as SubagentCard).childId)
            assertEquals(SkillPhase.Loaded, (card("skill") as SkillCard).phase)
            assertTrue(card("plugin:notes/save-note") is ToolCardView.GenericCard)
            assertEquals(2, (card("job_list") as DetailsCard).items.size)
            assertEquals(QuestionOutcome.Answered, (card("ask_user_question") as QuestionCard).outcome)

            val failures = calls.map { toolFailureOf(results[it.callId]) }
            assertTrue(failures.contains(ToolFailure.Denied(PermissionDenial.Rejected, null)))
            assertTrue(failures.any { it is ToolFailure.Denied && it.denial == PermissionDenial.AutoReviewRejected && it.reason != null })
            assertTrue(failures.any { it is ToolFailure.Failed && it.message?.startsWith("old_string was not found") == true })
            // The stopped turn's command has no result.
            assertEquals(1, calls.count { it.callId !in results })

            val runs = nodes.filterIsInstance<CommandNode>().filter { it.isRun }
            assertEquals(listOf(SystemLine.PermissionChanged("workspace-write"), SystemLine.Ran("/review")), runs.map { systemLineFor(it, nodes) })
            // The tail is the session's usual last message, so its row reads as before.
            assertEquals(
                "Done. " + "Eight new tests cover the queue.",
                nodes.filterIsInstance<AssistantMessageNode>().last().plainText,
            )
        } finally {
            servers.stop()
        }
    }
}
