package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.test.*
import kotlinx.io.Source
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TimedQuestionCapabilityTest {
    private class Transport : RpcTransport {
        val paths = mutableListOf<String>()
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            paths += path
            val id = WireJson.parseToJsonElement(body).jsonObject.getValue("rpcId")
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":$id,"result":{"ok":true,"value":true}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: Source, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }

    private fun failingMux(code: String) = RemoteStreamMux { sink ->
        object : WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
            override fun start() { sink.onOpen() }
            override fun close() = Unit
            override fun send(text: String): Boolean {
                val frame = WireJson.parseToJsonElement(text).jsonObject
                if (frame["type"]?.jsonPrimitive?.content == "open") {
                    sink.onMessage("""{"type":"error","streamId":${frame["streamId"]},"error":{"code":"$code","message":"stream refusal","details":{}}}""")
                }
                return true
            }
        }
    }.also { it.start() }

    @Test fun `missing question stream falls back while auth and transport failures remain visible`() = runTest {
        for (code in listOf("gateway/invocation-unavailable", "gateway/definition-unavailable", "capability-unavailable", "forbidden", "unauthenticated", "internal")) {
            val errors = mutableListOf<RpcError>()
            val availability = FeatureAvailability { errors += it }
            val mux = failingMux(code)
            val api = DshApiClient(Transport())
            var fallback: String? = null
            val owner = QuestionSessions(backgroundScope, { api }, { mux }, { "generation" },
                onCapability = { _, result -> availability.observe(HarnessFeature.TIMED_QUESTIONS, availability.generation(), result) },
                onUnavailable = { sid, eventId, _ -> fallback = "$sid/$eventId" })
            owner.requested("s", "event", AskUserQuestionRequestEvent(emptyList(), QuestionWait("call", true)))
            runCurrent()
            if (code in setOf("gateway/invocation-unavailable", "gateway/definition-unavailable", "capability-unavailable")) {
                assertEquals(false, availability.state(HarnessFeature.TIMED_QUESTIONS).value)
                assertEquals("s/event", fallback)
                assertTrue(errors.isEmpty())
            } else {
                assertNull(availability.state(HarnessFeature.TIMED_QUESTIONS).value)
                assertNull(fallback)
                assertEquals(code, errors.single().code)
            }
            owner.reset(); mux.close()
        }
    }

    @Test fun `blocking question still answers via the legacy event method without a timed stream`() = runTest {
        val transport = Transport()
        val api = DshApiClient(transport)
        val owner = QuestionSessions(backgroundScope, { api }, { null }, { "generation" },
            onCapability = { _, _ -> fail("Blocking questions must not probe timed methods") })
        owner.requested("s", "event", AskUserQuestionRequestEvent(emptyList(), QuestionWait("call", false)))
        assertTrue(owner.cards.value.single().ready)
        assertNull(owner.answer("s/call", AskUserQuestionAnswer(emptyList())))
        assertEquals(1, transport.paths.size)
        assertFalse(transport.paths.single().contains("userQuestions"))
        owner.reset()
    }
}
