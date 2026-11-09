package com.labteto.dshmobile.data

import com.labteto.dshmobile.core.wire.*
import com.labteto.dshmobile.core.wire.dto.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.io.Source
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuestionSessionsTest {
    private class Transport(private val receipt: CompletableDeferred<Unit>? = null) : RpcTransport {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override suspend fun post(path: String, body: String): RpcHttpResponse {
            val request = WireJson.parseToJsonElement(body).jsonObject
            calls += path to request
            receipt?.await()
            return RpcHttpResponse(200, """{"type":"server-response","rpcId":${request["rpcId"]},"result":{"ok":true,"value":true}}""")
        }
        override suspend fun <T> download(path: String, consume: (String?, String?, Source) -> T): T = error("unused")
        override suspend fun upload(path: String, contentType: String, contentLength: Long, body: Source, onProgress: ((Long) -> Unit)?): RpcHttpResponse = error("unused")
    }
    private fun mux(duration: Long) = RemoteStreamMux { sink ->
        object : WsChannel("http://stub/api/remote.mux", OkHttpClient(), sink) {
            override fun start() { sink.onOpen() }
            override fun close() = Unit
            override fun send(text: String): Boolean {
                val frame = WireJson.parseToJsonElement(text).jsonObject
                if (frame["type"]?.jsonPrimitive?.content == "open") sink.onMessage("""{"type":"item","streamId":${frame["streamId"]},"value":{"remainingMs":$duration}}""")
                return true
            }
        }
    }.also { it.start() }
    private val questions = listOf(AskUserQuestionItem("q", "Choose", options = listOf(AskUserQuestionOption("Yes"))))
    private fun view(state: String) = WireJson.encodeToJsonElement(UserQuestionsView.serializer(), UserQuestionsView(active = listOf(ContinuedQuestion("call", questions, state))))

    @Test fun `modern pending kinds track unopened sessions through every completion path`() = runTest {
        val api = DshApiClient(Transport())
        val owner = QuestionSessions(backgroundScope, { api }, { null }, { "generation" })
        fun request(call: String, items: List<AskUserQuestionItem> = questions) =
            owner.requested("unopened", "event-$call", AskUserQuestionRequestEvent(items, QuestionWait(call, false)))
        fun kinds() = owner.cards.value.pendingQuestionKinds()["unopened"].orEmpty()
        request("answer")
        request("answer") // Re-delivery is still one pending call.
        assertEquals(1, owner.cards.value.size)
        assertEquals(setOf("question"), kinds())
        request("cancel")
        owner.answer("unopened/answer", AskUserQuestionAnswer(emptyList()))
        assertEquals(setOf("question"), kinds()) // Another call is still open.
        owner.cancelled("event-cancel")
        assertTrue(kinds().isEmpty())
        val plan = questions.map { it.copy(intent = AskUserQuestionIntent.PlanReview("Yes")) }
        request("plan", plan)
        assertEquals(setOf("plan-review"), kinds())
        request("ordinary")
        assertEquals(setOf("question", "plan-review"), kinds())
        owner.projection("unopened", 1, WireJson.encodeToJsonElement(UserQuestionsView.serializer(),
            UserQuestionsView(active = listOf(ContinuedQuestion("plan", plan, "continued")))))
        assertEquals(setOf("question"), kinds())
        owner.projection("unopened", 2, WireJson.encodeToJsonElement(UserQuestionsView.serializer(),
            UserQuestionsView(settled = listOf(SettledQuestion("ordinary", emptyList())))))
        assertTrue(kinds().isEmpty())
        request("removed")
        owner.removeSession("unopened")
        assertTrue(kinds().isEmpty())
        request("reset")
        owner.reset()
        assertTrue(owner.cards.value.pendingQuestionKinds().isEmpty())
    }

    @Test fun `timed question marks session pending before claim attaches and clears on continuation`() = runTest {
        val api = DshApiClient(Transport())
        val mux = mux(500)
        val owner = QuestionSessions(backgroundScope, { api }, { mux }, { "generation" }, { testScheduler.currentTime })
        owner.requested("unopened", "event", AskUserQuestionRequestEvent(questions, QuestionWait("call", true)))
        assertEquals(setOf("question"), owner.cards.value.pendingQuestionKinds()["unopened"])
        runCurrent()
        owner.projection("unopened", 1, view("continued"))
        assertTrue(owner.cards.value.pendingQuestionKinds().isEmpty())
        owner.projection("unopened", 0, view("open"))
        assertTrue(owner.cards.value.pendingQuestionKinds().isEmpty())
        owner.reset(); mux.close()
    }

    @Test fun `settlement before the answer receipt wins and clears a queued reply`() = runTest {
        val receipt = CompletableDeferred<Unit>()
        val api = DshApiClient(Transport(receipt))
        val owner = QuestionSessions(backgroundScope, { api }, { null }, { "generation" })
        owner.projection("s", 1, view("continued"))
        val submitted = AskUserQuestionAnswer(listOf(AskUserQuestionAnswerItem("q", listOf("Yes"))))
        val response = async { owner.answer("s/call", submitted) }
        runCurrent()
        owner.inbox("s", WireJson.parseToJsonElement("""{"next-step":[{"source":{"kind":"user-question-reply","callId":"call"}}]}"""))
        assertTrue(owner.cards.value.single().queued)
        val settledAnswers = listOf(AskUserQuestionAnswerItem("q", custom = "Another client answered"))
        owner.projection("s", 2, WireJson.encodeToJsonElement(UserQuestionsView.serializer(),
            UserQuestionsView(settled = listOf(SettledQuestion("call", settledAnswers)))))
        assertFalse(owner.cards.value.single().queued)
        receipt.complete(Unit)
        assertNull(response.await())
        assertEquals("settled", owner.cards.value.single().state)
        assertEquals(settledAnswers, owner.cards.value.single().answers)
        assertFalse(owner.cards.value.single().queued)
        owner.inbox("s", WireJson.parseToJsonElement("""{"next-step":[{"source":{"kind":"user-question-reply","callId":"call"}}]}"""))
        assertFalse(owner.cards.value.single().queued)
    }

    @Test fun `newer inbox or continued projection prevents a late receipt from queuing again`() = runTest {
        for (updateInbox in listOf(true, false)) {
            val receipt = CompletableDeferred<Unit>()
            val api = DshApiClient(Transport(receipt))
            val owner = QuestionSessions(backgroundScope, { api }, { null }, { "generation" })
            owner.projection("s", 1, view("continued"))
            val response = async { owner.answer("s/call", AskUserQuestionAnswer(emptyList())) }
            runCurrent()
            if (updateInbox) owner.inbox("s", buildJsonObject {})
            else owner.projection("s", 2, view("continued"))
            receipt.complete(Unit)
            assertNull(response.await())
            assertEquals("continued", owner.cards.value.single().state)
            assertFalse(owner.cards.value.single().queued)
        }
    }

    @Test fun `countdown holds on focus and expires by rejection without manufacturing an answer`() = runTest {
        val transport = Transport(); val api = DshApiClient(transport); val mux = mux(500)
        val owner = QuestionSessions(backgroundScope, { api }, { mux }, { "generation" }, { testScheduler.currentTime })
        owner.requested("s", "event", AskUserQuestionRequestEvent(questions, QuestionWait("call", true)))
        runCurrent()
        owner.focus("s/call", true)
        advanceTimeBy(1000); runCurrent()
        assertEquals(500L, owner.cards.value.single().remainingMs)
        assertTrue(transport.calls.isEmpty())
        owner.focus("s/call", false)
        advanceTimeBy(600); runCurrent()
        assertEquals(1, transport.calls.size)
        assertTrue(transport.calls.single().second.toString().contains("ASK_TIMED_OUT"))
        assertFalse(transport.calls.single().second.toString().contains("selected"))
        owner.projection("s", 5, view("continued"))
        assertTrue(owner.cards.value.single().ready)
        owner.answer("s/call", AskUserQuestionAnswer(listOf(AskUserQuestionAnswerItem("q", listOf("Yes")))))
        assertEquals("/api/userQuestions/answer", transport.calls.last().first)
        assertTrue(owner.cards.value.single().queued)
        owner.reset(); mux.close()
    }

    @Test fun `hiding keeps a held call alive and reconnects preserve call identity`() = runTest {
        val transport = Transport(); val api = DshApiClient(transport); val mux = mux(300)
        var generation = "first"
        val owner = QuestionSessions(backgroundScope, { api }, { mux }, { generation }, { testScheduler.currentTime })
        val request = AskUserQuestionRequestEvent(questions, QuestionWait("call", true))
        owner.requested("s", "first-event", request); runCurrent()
        owner.hold("s/call"); owner.hide("s/call", true)
        advanceTimeBy(1000); runCurrent()
        assertTrue(transport.calls.isEmpty())
        owner.disconnect(); generation = "second"
        owner.requested("s", "second-event", request); runCurrent()
        owner.cancelled("first-event")
        assertTrue(owner.cards.value.single().held)
        assertTrue(owner.cards.value.single().ready)
        owner.projection("s", 10, view("continued"))
        owner.projection("s", 9, view("open"))
        assertEquals("continued", owner.cards.value.single().state)
        owner.reset(); assertTrue(owner.cards.value.isEmpty()); mux.close()
    }
}
