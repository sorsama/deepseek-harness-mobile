package com.labteto.dshmobile.mockharness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The demo harness the iOS session list and chat scenarios run against: what it lists, what a session's log holds,
 * and the reply it streams to a prompt. Its content is fixed and its times count back from the clock it is given, so
 * with a fixed clock two runs give the same bytes.
 */
class DemoHarnessTest {

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun call(base: String, method: String, args: String): JsonObject {
        val response = http.send(
            HttpRequest.newBuilder(URI("$base/api/$method"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"type":"client-request","rpcId":"1","method":"$method","payload":{"args":$args}}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(response.body(), 200, response.statusCode())
        val result = Json.parseToJsonElement(response.body()).jsonObject["result"]!!.jsonObject
        assertEquals(response.body(), "true", result["ok"]!!.jsonPrimitive.content)
        return result["value"]!!.jsonObject
    }

    private fun withDemo(block: suspend (CiFixtureServers) -> Unit) = runBlocking {
        val servers = CiFixtures.start(withDemo = true, demoClock = { NOW })
        try {
            block(servers)
        } finally {
            servers.stop()
        }
    }

    @Test
    fun theListHasTwelveTitledSessionsInTwoWorkspacesNewestFirst() = withDemo { servers ->
        val items = call(servers.demoUrl!!, "session/list", """{"_request":{}}""")["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(DemoHarness.SESSION_COUNT, items.size)
        assertEquals(12, items.size)
        assertEquals("demo-01", items.first()["sessionId"]!!.jsonPrimitive.content)
        assertEquals(
            "Fix the login screen crash",
            items.first()["projections"]!!.jsonObject["values"]!!.jsonObject["title"]!!.jsonPrimitive.content,
        )
        val times = items.map { it["updatedAt"]!!.jsonPrimitive.long }
        assertEquals(times.sortedDescending(), times)
        assertTrue(times.all { it < NOW })
        val cwds = items.map { it["cwd"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("C:\\Users\\demo\\mobile-app", "C:\\Users\\demo\\docs-site"), cwds)
        // Nothing in it depends on when it ran: the same clock gives the same answer.
        assertEquals(items, call(servers.demoUrl!!, "session/list", """{"_request":{}}""")["items"]!!.jsonArray.map { it.jsonObject })
    }

    @Test
    fun theFirstSessionHasThinkingAToolCallAndMarkdown() = withDemo { servers ->
        val page = call(
            servers.demoUrl!!,
            "session/page",
            """{"request":{"address":{"kind":"session","sessionId":"demo-01"},"throughSeq":100}}""",
        )
        val events = page["records"]!!.jsonArray.map { it.jsonObject["event"]!!.jsonObject }
        assertEquals(
            listOf(
                "turn/start", "user/message", "assistant/message", "tool/call", "tool/result", "assistant/message", "turn/end",
                "turn/start", "user/message", "assistant/message", "turn/end",
            ),
            events.map { it["type"]!!.jsonPrimitive.content },
        )
        assertEquals((0 until events.size).toList(), events.map { it["seq"]!!.jsonPrimitive.int })
        val firstReply = events[2]["data"]!!.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray.map {
            it.jsonObject["type"]!!.jsonPrimitive.content
        }
        assertEquals(listOf("reasoning", "text", "tool-call"), firstReply)
        val answer = events[5]["data"]!!.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray.single()
            .jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(answer.startsWith("## "))
        assertTrue("```kotlin" in answer)
        val gallery = events[9]["data"]!!.jsonObject["message"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("reasoning", "text"), gallery.map { it["type"]!!.jsonPrimitive.content })
        assertEquals(DemoHarness.GALLERY, gallery[1]["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun aPromptIsAnsweredOnTheFollowStreamAndStaysInTheLog() = withDemo { servers ->
        val items = LinkedBlockingQueue<JsonObject>()
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    items.add(Json.parseToJsonElement(buffer.toString()).jsonObject)
                    buffer.clear()
                }
                webSocket.request(1)
                return null
            }
        }
        val port = servers.demoPort!!
        val socket = http.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/api/remote.mux"), listener)
            .get(5, TimeUnit.SECONDS)
        try {
            socket.sendText(
                """{"type":"open","streamId":"f","endpoint":"session/follow","payload":{"args":{"request":""" +
                    """{"address":{"kind":"session","sessionId":"demo-02"},"assistantStream":true}}}}""",
                true,
            ).get(5, TimeUnit.SECONDS)
            val snapshot = items.poll(5, TimeUnit.SECONDS)!!["value"]!!.jsonObject
            assertEquals("snapshot", snapshot["type"]!!.jsonPrimitive.content)
            assertEquals(3, snapshot["cursor"]!!.jsonPrimitive.int)
            assertNotNull(snapshot["assistantStream"])

            call(
                servers.demoUrl!!,
                "session/prompt",
                """{"request":{"requestId":"r1","sessionId":"demo-02","mode":"queue","content":[{"type":"text","text":"Make it blue"}]}}""",
            )

            val seen = mutableListOf<String>()
            val streamed = StringBuilder()
            while (true) {
                val value = items.poll(10, TimeUnit.SECONDS)?.get("value")?.jsonObject ?: error("the reply stopped after $seen")
                val label = when (value["type"]!!.jsonPrimitive.content) {
                    "event" -> value["event"]!!.jsonObject["type"]!!.jsonPrimitive.content
                    "assistant-stream" -> {
                        val frame = value["frame"]!!.jsonObject
                        val chunk = frame["chunk"]?.jsonObject
                        if (chunk?.get("type")?.jsonPrimitive?.content == "text-delta") streamed.append(chunk["text"]!!.jsonPrimitive.content)
                        "stream/" + frame["type"]!!.jsonPrimitive.content
                    }
                    else -> "other"
                }
                if (seen.lastOrNull() != label) seen.add(label)
                if (label == "turn/end") break
            }
            assertEquals(
                listOf("turn/start", "user/message", "stream/start", "stream/chunk", "stream/end", "assistant/message", "turn/end"),
                seen,
            )
            assertEquals(DemoHarness.REPLY, streamed.toString())
            val log = servers.demo!!.log("demo-02")
            assertEquals(8, log.size)
            assertEquals("Make it blue", log[5]["data"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done")
        }
    }

    private companion object {
        const val NOW = 1_795_000_000_000L
    }
}
