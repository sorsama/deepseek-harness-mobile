package com.labteto.dshmobile.core.wire

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListLargeResponseTest {
    @Test fun sessionListKeepsTitlesWithoutRetainingLargeUnusedProjection() = runBlocking {
        RecordingServer().route("/api/session/list") { exchange, _ ->
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("""{"rpcId":"test","result":{"ok":true,"value":{"items":[{"sessionId":"one","updatedAt":1,"running":false,"blank":false,"projections":{"asOfSeq":1,"values":{"unused":""".toByteArray())
                out.write(34)
                val chunk = ByteArray(64 * 1024) { 120 }
                repeat(257) { out.write(chunk) }
                out.write("""","title":"Example"}}}]}}}""".toByteArray())
            }
        }.start().use { server ->
            val result = DshApiClient(TransportKind.OKHTTP.rpc(server.baseUrl)).sessionList()
            val row = (result as RpcResult.Ok).value.items.single()
            assertEquals("one", row.sessionId)
            assertEquals(setOf("title"), row.projections?.values?.keys)
        }
    }
}
