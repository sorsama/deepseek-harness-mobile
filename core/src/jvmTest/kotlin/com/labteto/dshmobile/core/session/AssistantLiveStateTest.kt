package com.labteto.dshmobile.core.session

import com.labteto.dshmobile.core.session.AssistantLiveState.Change
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.core.wire.decodeFromJsonElement
import com.labteto.dshmobile.core.wire.dto.AssistantStreamOutcome
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamBaseline
import com.labteto.dshmobile.core.wire.dto.SessionAssistantStreamFrame
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live half of session format v2: the attempt being written, kept only until its durable
 * settlement lands. Every branch here is a case the web client's `ClientAssistantStream` also
 * takes, minus the reconnect it performs where this drops the preview.
 */
class AssistantLiveStateTest {

    private fun start(attempt: String = "a1", revision: Int = 1, after: Int = 4, turn: Int = 1, step: Int = 1) =
        SessionAssistantStreamFrame.Start(attemptId = attempt, revision = revision, startedAfterSeq = after, turn = turn, step = step)

    private fun chunk(index: Int, text: String, attempt: String = "a1", revision: Int = index + 2) =
        SessionAssistantStreamFrame.Chunk(
            attemptId = attempt,
            revision = revision,
            index = index,
            time = 1_000L + index,
            chunk = buildJsonObject {
                put("type", JsonPrimitive("text-delta"))
                put("index", JsonPrimitive(0))
                put("text", JsonPrimitive(text))
            },
        )

    private fun end(index: Int, outcome: AssistantStreamOutcome, attempt: String = "a1", revision: Int = 99) =
        SessionAssistantStreamFrame.End(attemptId = attempt, revision = revision, index = index, outcome = outcome)

    private fun AssistantLiveState.texts(): List<String> =
        transientEnvelopes().map { it.data.jsonObject.getValue("chunk").jsonObject.getValue("text").jsonPrimitive.content }

    @Test
    fun `chunks accumulate into transient rows for the open attempt`() {
        val state = AssistantLiveState()
        assertEquals(Change.NONE, state.accept(start()))
        assertEquals(Change.CHANGED, state.accept(chunk(0, "Hel")))
        assertEquals(Change.CHANGED, state.accept(chunk(1, "lo")))
        assertEquals(listOf("Hel", "lo"), state.texts())
        val row = state.transientEnvelopes().first()
        assertEquals("assistant/chunk", row.type)
        assertEquals(1, row.data.jsonObject.getValue("turn").jsonPrimitive.content.toInt())
        assertEquals(1, row.data.jsonObject.getValue("step").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a committed end closes the attempt and clears its rows`() {
        val state = AssistantLiveState()
        state.accept(start())
        state.accept(chunk(0, "hi"))
        val change = state.accept(end(1, AssistantStreamOutcome.Committed(eventType = "assistant/message", seq = 7)))
        assertEquals(Change.SETTLED, change)
        assertFalse(state.active)
        assertTrue(state.transientEnvelopes().isEmpty())
    }

    @Test
    fun `an abandoned end reports the rows as simply gone`() {
        val state = AssistantLiveState()
        state.accept(start())
        state.accept(chunk(0, "hi"))
        assertEquals(Change.ABANDONED, state.accept(end(1, AssistantStreamOutcome.Abandoned())))
        assertFalse(state.active)
    }

    @Test
    fun `a chunk out of order drops the preview instead of showing a hole`() {
        val state = AssistantLiveState()
        state.accept(start())
        state.accept(chunk(0, "one"))
        assertEquals(Change.RESET, state.accept(chunk(2, "three")))
        assertFalse(state.active)
        // Later chunks of the same attempt have nothing to attach to and are ignored.
        assertEquals(Change.NONE, state.accept(chunk(3, "four")))
    }

    @Test
    fun `an end whose count disagrees is a continuity fault`() {
        val state = AssistantLiveState()
        state.accept(start())
        state.accept(chunk(0, "one"))
        assertEquals(Change.RESET, state.accept(end(5, AssistantStreamOutcome.Committed(eventType = "assistant/message", seq = 9))))
    }

    @Test
    fun `frames for an attempt that was never started are ignored`() {
        // A follower mounted after the host saw the start: the settlement publishes directly.
        val state = AssistantLiveState()
        assertEquals(Change.NONE, state.accept(chunk(0, "late")))
        assertEquals(Change.NONE, state.accept(end(1, AssistantStreamOutcome.Abandoned())))
        assertFalse(state.active)
    }

    @Test
    fun `a start while one is open replaces it`() {
        val state = AssistantLiveState()
        state.accept(start(attempt = "a1"))
        state.accept(chunk(0, "old"))
        assertEquals(Change.RESET, state.accept(start(attempt = "a2", revision = 5)))
        assertEquals("a2", state.attemptId)
        assertTrue(state.transientEnvelopes().isEmpty())
        assertEquals(Change.CHANGED, state.accept(chunk(0, "new", attempt = "a2", revision = 6)))
        assertEquals(listOf("new"), state.texts())
    }

    @Test
    fun `the durable settlement retires the attempt the moment it lands`() {
        val state = AssistantLiveState()
        state.accept(start(after = 4, turn = 2, step = 3))
        state.accept(chunk(0, "hi"))
        // Wrong step: some earlier attempt's message, not this one's.
        assertFalse(state.acceptDurable("assistant/message", turn = 2, step = 2, seq = 6, surfaceOp = "append"))
        // Logged before the attempt started: cannot be its settlement.
        assertFalse(state.acceptDurable("assistant/message", turn = 2, step = 3, seq = 4, surfaceOp = "append"))
        // A positional replacement is compaction, never a model attempt.
        assertFalse(state.acceptDurable("assistant/message", turn = 2, step = 3, seq = 6, surfaceOp = """{"op":"replace","start":1,"end":2}"""))
        assertTrue(state.active)
        assertTrue(state.acceptDurable("assistant/message", turn = 2, step = 3, seq = 6, surfaceOp = "append"))
        assertFalse(state.active)
        // Once retired, the late end frame has nothing to do.
        assertEquals(Change.NONE, state.accept(end(1, AssistantStreamOutcome.Committed(eventType = "assistant/message", seq = 6))))
    }

    @Test
    fun `an attempt that settles without a message is retired by the attempt event`() {
        val state = AssistantLiveState()
        state.accept(start(after = 4, turn = 1, step = 1))
        state.accept(chunk(0, "partial"))
        assertTrue(state.acceptDurable("assistant/attempt", turn = 1, step = 1, seq = 5, surfaceOp = null))
        assertFalse(state.active)
    }

    @Test
    fun `a reconnect baseline rebuilds the partial reply it missed`() {
        val baseline = decodeFromJsonElement<SessionAssistantStreamBaseline>(
            WireJson.parseToJsonElement(
                """{"revision":7,"activeAttempt":{"attemptId":"a9","startedAfterSeq":12,"turn":3,"step":1,"nextIndex":2,
                    "stream":[{"type":"text-chunks","time0":1,"index":0,"dt":[1,1],"texts":["so"," far"," and-then"]}]}}""",
            ),
        )
        val state = AssistantLiveState()
        state.seed(baseline)
        assertTrue(state.active)
        assertEquals("a9", state.attemptId)
        assertEquals(7, state.revision)
        // Only `nextIndex` members are kept: the count is what the continuity check trusts.
        assertEquals(listOf("so", " far"), state.texts())
        // The live tail continues exactly where the baseline said it would.
        assertEquals(Change.CHANGED, state.accept(chunk(2, " more", attempt = "a9", revision = 8)))
        assertEquals(listOf("so", " far", " more"), state.texts())
        assertTrue(state.acceptDurable("assistant/message", turn = 3, step = 1, seq = 13, surfaceOp = "append"))
    }

    @Test
    fun `a baseline with no open attempt seeds nothing`() {
        val state = AssistantLiveState()
        state.accept(start())
        state.accept(chunk(0, "stale"))
        state.seed(SessionAssistantStreamBaseline(revision = 3))
        assertFalse(state.active)
        assertEquals(3, state.revision)
        state.seed(null)
        assertFalse(state.active)
    }
}
