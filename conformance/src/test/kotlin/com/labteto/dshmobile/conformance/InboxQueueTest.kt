package com.labteto.dshmobile.conformance

import com.labteto.dshmobile.core.wire.RpcResult
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.dto.InboxProjection
import com.labteto.dshmobile.core.wire.dto.PromptContentPart
import com.labteto.dshmobile.core.wire.dto.SessionControlFrame
import com.labteto.dshmobile.core.wire.dto.SessionCreateRequest
import com.labteto.dshmobile.core.wire.dto.SessionPromptRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * The queue dock's contents, against a harness that no longer sends a queue.
 *
 * Through 0.1.6-alpha.1 the host computed this for us: `session/control`'s baseline carried a
 * `queues` map and every change arrived as a `{"type":"queue"}` frame, each item already tagged
 * with its placement. Current master deletes both and publishes the agent's inbox as an ordinary
 * projection instead. A client that reads only the old shapes does not fail — it shows an empty
 * dock, forever, because an absent projection and an empty queue are the same picture. That is the
 * kind of break this whole module exists to catch, and it is why this test asserts against a real
 * harness rather than a mock that would have been written from the same misreading.
 *
 * The agent is kept busy with a model that never answers, so the second prompt has somewhere to
 * wait. Without that the agent takes it immediately and there is no queue to look at.
 */
class InboxQueueTest {

    private lateinit var model: MockModel
    private lateinit var harness: HarnessProcess
    private lateinit var client: HarnessClient

    @Before
    fun boot() {
        assumeTrue("no built harness checkout; see HarnessProcess", HarnessProcess.available())
        // `stall` never answers, so the first turn stays open and the second prompt has to queue.
        model = MockModel.start(sequence = listOf("stall"))
        harness = HarnessProcess.start(model)
        client = HarnessClient(harness)
    }

    @After
    fun stop() {
        if (this::client.isInitialized) client.close()
        if (this::harness.isInitialized) harness.close()
    }

    private suspend fun prompt(sessionId: String, text: String, mode: String) =
        client.api.sessionPrompt(
            SessionPromptRequest(
                requestId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                mode = mode,
                content = listOf(PromptContentPart.Text(text)),
            ),
        )

    @Test
    fun `work waiting behind a busy agent reaches the dock through the inbox projection`() =
        runBlocking {
            val created = client.api.sessionCreate(SessionCreateRequest(cwd = harness.workspacePath))
            val sessionId = (created as? RpcResult.Ok)?.value?.sessionId
                ?: error("could not create a session: $created")

            client.mux.start()
            withTimeout(SOCKET_BUDGET_MS) { client.mux.awaitOpen() }
            val control = client.mux.open("session/control", JsonObject(emptyMap()))

            try {
                // The baseline first, then the projection frames that follow it.
                val baseline = withTimeout(SOCKET_BUDGET_MS) { control.receive() }
                    ?: error("session/control ended before its baseline")
                val decoded = WireJson.decodeFromJsonElement(SessionControlFrame.serializer(), baseline)
                assertTrue("expected a baseline first, got $decoded", decoded is SessionControlFrame.Baseline)

                // One prompt to occupy the agent, one to queue behind it.
                assertTrue("the first prompt was refused", prompt(sessionId, "keep busy", "queue") is RpcResult.Ok)
                delay(SETTLE_MS)
                assertTrue("the queued prompt was refused", prompt(sessionId, QUEUED_TEXT, "queue") is RpcResult.Ok)

                val items = withTimeout(INBOX_BUDGET_MS) { awaitQueuedItem(control, sessionId) }
                assertTrue(
                    "no pending item carried the queued text; got ${items.map { it.placement to it.id }}",
                    items.isNotEmpty(),
                )
                assertEquals(
                    "work waiting for the next turn is queued, not steering",
                    InboxProjection.QUEUED,
                    items.first().placement,
                )
            } finally {
                control.cancel()
            }
        }

    /**
     * Read control frames until the session's inbox projection carries something pending.
     *
     * The projection arrives on its own schedule — the host folds it after the durable splice
     * commits — so this waits for it rather than sampling once and calling an empty inbox a
     * failure.
     */
    private suspend fun awaitQueuedItem(
        control: com.labteto.dshmobile.core.wire.RemoteStream,
        sessionId: String,
    ): List<com.labteto.dshmobile.core.wire.dto.QueuedInboxItem> {
        while (true) {
            val raw = control.receive() ?: error("session/control ended while waiting for the inbox")
            val frame = runCatching {
                WireJson.decodeFromJsonElement(SessionControlFrame.serializer(), raw)
            }.getOrNull() ?: continue
            if (frame !is SessionControlFrame.Projection) continue
            if (frame.sessionId != sessionId) continue
            // 0.2.2 folds every projection key one committed event touched into one `changes`
            // frame; older hosts send one key per frame. The DTO normalizes both to `changes`, so
            // this reads the same entry whether the host under test is before or after the split.
            val inbox = frame.changes.firstOrNull { it.key == "inbox" } ?: continue
            val items = InboxProjection.itemsFrom(inbox.value)
            if (items.isNotEmpty()) return items
        }
    }

    private companion object {
        const val SOCKET_BUDGET_MS = 10_000L
        const val INBOX_BUDGET_MS = 60_000L

        /** Long enough for the first prompt to be claimed before the second one is sent. */
        const val SETTLE_MS = 3_000L

        const val QUEUED_TEXT = "this one waits"
    }
}
