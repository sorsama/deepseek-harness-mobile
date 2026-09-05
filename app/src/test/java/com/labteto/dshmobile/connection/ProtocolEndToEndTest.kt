package com.labteto.dshmobile.connection

import com.labteto.dshmobile.core.wire.ConnectionLoop
import com.labteto.dshmobile.core.wire.ConnectionState
import com.labteto.dshmobile.core.wire.DshApiClient
import com.labteto.dshmobile.core.wire.GenerationFailure
import com.labteto.dshmobile.core.wire.HandshakeStep
import com.labteto.dshmobile.core.wire.HostGeneration
import com.labteto.dshmobile.core.wire.LoopConfig
import com.labteto.dshmobile.core.wire.LoopSinks
import com.labteto.dshmobile.core.wire.OkHttpRpcTransport
import com.labteto.dshmobile.core.wire.RemoteStreamMux
import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.WsChannel
import com.labteto.dshmobile.core.wire.dto.APPROVAL_REQUEST_EVENT
import com.labteto.dshmobile.core.wire.dto.ApprovalOutcome
import com.labteto.dshmobile.core.wire.dto.REMOTE_STREAM_MUX_PATH
import com.labteto.dshmobile.core.wire.dto.RemoteEventFrame
import com.labteto.dshmobile.core.wire.dto.RemoteEventOutcome
import com.labteto.dshmobile.core.wire.dto.SessionAddress
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrame
import com.labteto.dshmobile.core.wire.dto.SessionFollowFrameSerializer
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import com.labteto.dshmobile.core.wire.dto.SessionPromptValue
import com.labteto.dshmobile.core.wire.dto.SubagentPromptRequest
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamFrame
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.newPromptRequestId
import com.labteto.dshmobile.mockharness.MockHarness
import com.labteto.dshmobile.core.session.AssistantLiveState
import com.labteto.dshmobile.core.session.AssistantMessageNode
import com.labteto.dshmobile.core.session.EventFold
import com.labteto.dshmobile.data.wireEventToEnvelope
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real client against the rewritten mock harness, over a real socket.
 *
 * Every other test in this repo exercises one layer with the next one stubbed. This one runs the
 * actual [RemoteStreamMux], [ConnectionLoop] and [DshApiClient] against [MockHarness] on loopback,
 * which is the only place the 0.1.2 handshake is checked end to end: the mux opening, `$events`
 * answering `ready`, a unary call on the new path shape, a session journal, and an approval
 * answered back through `$events/result`.
 *
 * It is not a substitute for the real harness — the mock is written from reading the upstream
 * source, so a misreading would be baked into both sides. What it does catch is the two halves of
 * *this* repo disagreeing, which is what a rewrite this size gets wrong.
 */
class ProtocolEndToEndTest {

    private lateinit var harness: MockHarness
    private var port: Int = -1
    private val http = OkHttpClient()

    private val baseUrl get() = "http://127.0.0.1:$port"

    @Before
    fun setUp() = runBlocking {
        harness = MockHarness(port = 0)
        port = harness.start()
    }

    @After
    fun tearDown() = runBlocking { harness.stop() }

    private fun mux() = RemoteStreamMux { sink -> WsChannel("$baseUrl$REMOTE_STREAM_MUX_PATH", http, sink) }

    private fun client() = DshApiClient(OkHttpRpcTransport(baseUrl, http, 5_000, 5_000))

    private class Recorder : LoopSinks {
        val connected = CopyOnWriteArrayList<HostGeneration>()
        val frames = CopyOnWriteArrayList<RemoteEventFrame>()
        val failures = CopyOnWriteArrayList<GenerationFailure>()
        override fun onEventFrame(frame: RemoteEventFrame) {
            frames.add(frame)
        }
        override fun onConnected(generation: HostGeneration) {
            connected.add(generation)
        }
        override fun onStateChange(state: ConnectionState) = Unit
        override fun onHandshakeStep(step: HandshakeStep) = Unit
        override fun onGenerationFailed(attempt: Int, failure: GenerationFailure) {
            failures.add(failure)
        }
    }

    /**
     * One complete v2 follow snapshot: a plain event record, a v2 header, and the opted-in
     * assistant baseline describing an attempt caught mid-stream with one chunk already sent.
     */
    private fun snapshotFrame() = buildJsonObject {
        put("type", "snapshot")
        put("cursor", 10)
        put("hasMore", false)
        putJsonObject("header") {
            put("version", 2)
            put("id", "s1")
            put("createdAt", 1L)
            put("isSeeded", false)
        }
        putJsonObject("projections") {
            put("asOfSeq", 10)
            putJsonObject("values") { }
        }
        putJsonArray("records") {
            addJsonObject {
                put("type", "event")
                putJsonObject("event") {
                    put("type", "turn/start")
                    put("seq", 10)
                    put("time", 1_000L)
                    putJsonObject("data") { put("turn", 1) }
                }
            }
        }
        putJsonObject("assistantStream") {
            put("revision", 2)
            putJsonObject("activeAttempt") {
                put("attemptId", "s1:1")
                put("startedAfterSeq", 10)
                put("turn", 1)
                put("step", 1)
                put("nextIndex", 1)
                putJsonArray("stream") {
                    addJsonObject {
                        put("type", "text-chunks")
                        put("time0", 1_001L)
                        put("index", 0)
                        putJsonArray("dt") { }
                        putJsonArray("texts") { add("Hel") }
                    }
                }
            }
        }
    }

    private fun chunkFrame(index: Int, text: String, revision: Int) = buildJsonObject {
        put("type", "chunk")
        put("attemptId", "s1:1")
        put("revision", revision)
        put("index", index)
        put("time", 1_000L + index)
        putJsonObject("chunk") {
            put("type", "text-delta")
            put("index", 0)
            put("text", text)
        }
    }

    private suspend fun await(predicate: () -> Boolean): Boolean =
        withTimeoutOrNull(10_000) {
            while (!predicate()) kotlinx.coroutines.delay(10)
            true
        } ?: false

    @Test
    fun `the handshake completes over a real socket`() = runBlocking {
        val recorder = Recorder()
        val loop = ConnectionLoop({ mux() }, recorder, LoopConfig(delay = { }))
        loop.start()
        try {
            assertTrue("never connected; failures=${recorder.failures}", await { recorder.connected.isNotEmpty() })
            val generation = recorder.connected.first()
            assertEquals("C:\\Users\\demo", generation.description.home)
            assertTrue(generation.clientId.isNotBlank())
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `a unary call reaches the new namespace path`() = runBlocking {
        harness.remote("session", "list", setOf("_request")) {
            buildJsonObject {
                putJsonArray("items") {
                    addJsonObject {
                        put("sessionId", "s1")
                        put("updatedAt", 5L)
                        put("running", false)
                        put("blank", false)
                    }
                }
            }
        }
        when (val result = client().sessionList()) {
            is RpcResult.Ok -> assertEquals("s1", result.value.items.single().sessionId)
            is RpcResult.Err -> error("session/list failed: ${result.error.code} ${result.error.message}")
        }
    }

    @Test
    fun `a prompt reaches the host and carries the identity it requires`() = runBlocking {
        // The one call every other test in this repo left alone. Connecting, listing, reading
        // history and choosing a model all worked against a real 0.1.2 harness while sending a
        // message could not, because no test and no mock endpoint ever sent one — so the client's
        // request object was missing a field the host declares required and nothing said so.
        val result = client().sessionPrompt(
            SessionPromptRequest(
                requestId = newPromptRequestId(),
                sessionId = "s1",
                mode = "queue",
                content = listOf(PromptContentPart.Text("hello")),
                clientTimeZone = "Asia/Bangkok",
            ),
        )
        when (result) {
            is RpcResult.Ok -> assertTrue(result.value.accepted)
            is RpcResult.Err -> error("session/prompt failed: ${result.error.code} ${result.error.message}")
        }
        val received = harness.sessionPrompts.single()
        assertTrue(received["requestId"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("s1", received["sessionId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a child prompt reaches the host and carries one too`() = runBlocking {
        val result = client().subagentPrompt(
            SubagentPromptRequest(
                requestId = newPromptRequestId(),
                parentSessionId = "s1",
                childSessionId = "c1",
                content = listOf(PromptContentPart.Text("carry on")),
                clientTimeZone = "Asia/Bangkok",
            ),
        )
        when (result) {
            is RpcResult.Ok -> assertTrue(result.value.messageId.isNotBlank())
            is RpcResult.Err -> error("subagents/prompt failed: ${result.error.code} ${result.error.message}")
        }
        assertTrue(harness.subagentPrompts.single()["requestId"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `a prompt without the required identity is refused the way the host refuses it`() = runBlocking {
        // The regression itself, from the client's side: the host decodes `request` against a
        // strict codec, so a missing required field fails at the boundary and the text never
        // reaches an agent. Sent as a raw args object because the typed request can no longer
        // express the broken shape — which is the point of making the field non-optional.
        val result = client().call(
            "session/prompt",
            buildJsonObject {
                putJsonObject("request") {
                    put("sessionId", "s1")
                    put("mode", "queue")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "hello")
                        }
                    }
                }
            },
            SessionPromptValue.serializer(),
        )
        val error = (result as RpcResult.Err).error
        // Since 0.1.3 the shape mismatch has its own namespaced code; the message still names
        // only the field, which is exactly why a client cannot probe for what was wrong inside it
        // and must send the declared shape.
        assertEquals("gateway/input-invalid", error.code)
        assertEquals(
            "typert gateway: session/prompt: wire field \"request\" failed boundary validation",
            error.message,
        )
        assertTrue(harness.sessionPrompts.isEmpty())
    }

    @Test
    fun `a follow generation streams a reply from its baseline through the live frames`() = runBlocking {
        // The shape 0.1.3 introduced that no earlier client ever saw: no durable deltas, a
        // baseline that describes an attempt caught mid-stream, then dense assistant frames.
        // Driving it through the real mux proves the frame union decodes and the live state
        // folds it into the provisional message the transcript shows.
        val loop = ConnectionLoop({ mux() }, Recorder(), LoopConfig(delay = { }))
        val muxHandle = mux()
        muxHandle.start()
        muxHandle.awaitOpen()
        try {
            val stream = muxHandle.open(
                "session/follow",
                buildJsonObject {
                    putJsonObject("request") {
                        put("address", buildJsonObject { put("kind", "session"); put("sessionId", "s1") })
                        put("assistantStream", true)
                    }
                },
            )
            // `open` travels over the socket asynchronously, so the mock may not have
            // registered the stream when the first push runs. Push until the client actually
            // sees a frame rather than sleeping on a guess.
            val pusher = launch {
                while (isActive) {
                    harness.pushStream("session/follow", snapshotFrame())
                    delay(50)
                }
            }
            val item = withTimeoutOrNull(10_000) { stream.receive() }
            pusher.cancel()
            if (item == null) error("no follow frame arrived")
            val frame = decodeFromJsonElement(SessionFollowFrameSerializer, item)
            assertTrue("was $frame", frame is SessionFollowFrame.Snapshot)
            val snapshot = frame as SessionFollowFrame.Snapshot
            assertEquals(10, snapshot.cursor)
            val durable = snapshot.records.map { wireEventToEnvelope(it.event) }
            assertEquals(listOf("turn/start"), durable.map { it.type })

            val live = AssistantLiveState()
            live.seed(snapshot.assistantStream)
            assertEquals("s1:1", live.attemptId)

            // Then the live tail: one more chunk, and the terminal marker naming the settlement.
            harness.pushAssistantStream(chunkFrame(index = 1, text = "lo", revision = 3))
            harness.pushAssistantStream(
                buildJsonObject {
                    put("type", "end")
                    put("attemptId", "s1:1")
                    put("revision", 4)
                    put("index", 2)
                    putJsonObject("outcome") {
                        put("kind", "committed")
                        put("eventType", "assistant/message")
                        put("seq", 11)
                    }
                },
            )
            val frames = mutableListOf<SessionFollowFrame>()
            while (frames.size < 2) {
                val next = withTimeoutOrNull(10_000) { stream.receive() } ?: error("live frame did not arrive")
                val decoded = decodeFromJsonElement(SessionFollowFrameSerializer, next)
                // A duplicate opening snapshot from the pusher may still be in flight; skip it.
                if (decoded is SessionFollowFrame.Snapshot) continue
                frames.add(decoded)
            }
            val chunk = (frames[0] as SessionFollowFrame.AssistantStream).frame as SessionAssistantStreamFrame.Chunk
            assertEquals(1, chunk.index)
            live.accept(chunk)
            // Baseline prefix plus the live chunk read as one streaming message, after the
            // durable window and without moving its cursor.
            val provisional = EventFold("s1").fold(durable, live.transientEnvelopes())
            assertEquals(10L, provisional.lastSeq)
            val streaming = provisional.nodes.last() as AssistantMessageNode
            assertTrue(streaming.streaming)
            assertEquals("Hello", streaming.plainText)

            val end = (frames[1] as SessionFollowFrame.AssistantStream).frame as SessionAssistantStreamFrame.End
            assertEquals(AssistantLiveState.Change.SETTLED, live.accept(end))
            assertTrue(live.transientEnvelopes().isEmpty())
        } finally {
            muxHandle.close()
            loop.stop()
        }
    }

    @Test
    fun `a file is staged through the raw-byte route and cited by receipt`() = runBlocking {
        // The one non-envelope write channel, over the real transport: octets up, a bare result
        // back, and the receipt is what the prompt carries — never the bytes.
        val uploaded = client().uploadFileBinary(
            sessionId = "s1",
            name = "notes.txt",
            contentLength = 5,
            body = ByteArrayInputStream("hello".toByteArray()),
        )
        val receipt = (uploaded as? RpcResult.Ok)?.value ?: error("upload failed: $uploaded")
        assertEquals("notes.txt", receipt.file.name)
        assertEquals(5L, receipt.file.bytes)
        assertEquals("hello", harness.fileUploads.single().bytes.decodeToString())

        val sent = client().sessionPrompt(
            SessionPromptRequest(
                requestId = newPromptRequestId(),
                sessionId = "s1",
                mode = "queue",
                content = listOf(PromptContentPart.Text("see attached"), PromptContentPart.File(receipt.receiptId)),
            ),
        )
        assertTrue("prompt failed: $sent", sent is RpcResult.Ok)
        val part = harness.sessionPrompts.single()["content"]!!.jsonArray[1].jsonObject
        assertEquals("file", part["type"]!!.jsonPrimitive.content)
        assertEquals(receipt.receiptId, part["receiptId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a route nobody claims falls back to the Remote form`() = runBlocking {
        // A relay that does not proxy the raw-byte route answers 404. The client reads that as a
        // missing capability rather than a broken link, and the encoded Remote still stages the
        // file — which is the whole reason it exists beside the route.
        harness.refuseBinaryUploads = true
        val streamed = client().uploadFileBinary("s1", "a.bin", 3, ByteArrayInputStream("abc".toByteArray()))
        assertEquals("capability-unavailable", (streamed as RpcResult.Err).error.code)

        val encoded = client().fileUploadEncoded(
            "s1",
            com.labteto.dshmobile.core.wire.dto.EncodedFileUploadRequest(
                data = java.util.Base64.getEncoder().encodeToString("abc".toByteArray()),
                name = "a.bin",
            ),
        )
        assertEquals("a.bin", (encoded as RpcResult.Ok).value.file.name)
        assertEquals("abc", harness.fileUploads.single().bytes.decodeToString())
    }

    @Test
    fun `an approval waterfall is delivered and answered through the events result endpoint`() = runBlocking {
        val recorder = Recorder()
        val loop = ConnectionLoop({ mux() }, recorder, LoopConfig(delay = { }))
        loop.start()
        try {
            assertTrue(await { recorder.connected.isNotEmpty() })
            val generation = recorder.connected.first()

            harness.pushEvent(
                buildJsonObject {
                    put("type", "waterfall")
                    put("event", APPROVAL_REQUEST_EVENT)
                    put("eventId", "evt-approval")
                    put("agentId", "s1")
                    putJsonObject("request") {
                        put("toolName", "bash")
                        put("reason", "runs a command")
                    }
                },
            )
            assertTrue(
                "no waterfall arrived",
                await { recorder.frames.any { it is RemoteEventFrame.Waterfall } },
            )
            val waterfall = recorder.frames.filterIsInstance<RemoteEventFrame.Waterfall>().first()
            assertEquals(APPROVAL_REQUEST_EVENT, waterfall.event)
            assertEquals("s1", waterfall.agentId)
            assertEquals("bash", waterfall.request["toolName"]!!.jsonPrimitive.content)

            // The answer is a bare outcome string, bound to this generation by its clientId.
            val answered = client().answerEvent(
                clientId = generation.clientId,
                eventId = waterfall.eventId,
                outcome = RemoteEventOutcome.Result(value = JsonPrimitive(ApprovalOutcome.ALLOWED_ONCE)),
            )
            assertTrue("answer was refused: $answered", answered is RpcResult.Ok)
        } finally {
            loop.stop()
        }
    }

    @Test
    fun `an answer from a retired generation is refused`() = runBlocking {
        // The whole point of binding a reply to a clientId: an answer typed before a reconnect
        // must not resolve a request the host has since replayed to the new generation.
        harness.pushEvent(
            buildJsonObject {
                put("type", "waterfall")
                put("event", "user-questions/request")
                put("eventId", "evt-stale")
                put("agentId", "s1")
                putJsonObject("request") {
                    putJsonArray("questions") {
                        addJsonObject { put("id", "q1"); put("question", "which?") }
                    }
                }
            },
        )
        val result = client().answerEvent(
            clientId = "a-generation-that-never-existed",
            eventId = "evt-stale",
            outcome = RemoteEventOutcome.Result(value = buildJsonObject { putJsonArray("answers") { } }),
        )
        assertTrue("was $result", result is RpcResult.Err)
        assertEquals("stale-generation", (result as RpcResult.Err).error.code)
    }

    @Test
    fun `a session address for a subagent survives the round trip`() = runBlocking {
        // `subagents/history` is gone; one address protocol covers a child transcript, and its
        // encoding is what a page read depends on.
        val address: SessionAddress = SessionAddress.Subagent(
            parentSessionId = "parent",
            childSessionId = "child",
            mode = "continuable",
        )
        val encoded = com.labteto.dshmobile.core.wire.encodeToJsonElement(
            SessionAddress.serializer(),
            address,
        ).jsonObject
        assertEquals("subagent", encoded["kind"]!!.jsonPrimitive.content)
        assertEquals("parent", encoded["parentSessionId"]!!.jsonPrimitive.content)
        val decoded = decodeFromJsonElement(SessionAddress.serializer(), encoded)
        assertEquals(address, decoded)
    }
}
