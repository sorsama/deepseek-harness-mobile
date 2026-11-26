package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.session.AssistantMessageNode
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo harness speaks raw JSON, so this holds it to the client's own DTOs and fold: the list decodes into twelve
 * titled rows, and the first session's log folds into thinking, a tool call with its result, and a markdown answer.
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
}
