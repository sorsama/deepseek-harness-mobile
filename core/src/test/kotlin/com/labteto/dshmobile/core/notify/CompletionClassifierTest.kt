package com.labteto.dshmobile.core.notify

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionClassifierTest {

    private val classifier = CompletionClassifier()

    private fun event(type: String, seq: Long, data: kotlinx.serialization.json.JsonObject): SessionEventEnvelope =
        SessionEventEnvelope(type, seq, seq, data)

    @Test
    fun turnCompletedFires() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "completed") }
        }))
        assertTrue(result is CompletionEvent.TurnComplete)
        assertEquals("turn:s1:4", result!!.dedupKey)
    }

    @Test
    fun abortedTurnDoesNotFire() {
        val result = classifier.classifyEvent("s1", event("turn/end", 4, buildJsonObject {
            put("turn", 1)
            putJsonObject("reason") { put("kind", "aborted") }
        }))
        assertNull(result)
    }

    @Test
    fun goalCompleteFires() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("objective", "ship it"); put("phase", "complete")
            }
        }))
        assertTrue(result is CompletionEvent.GoalComplete)
        assertEquals("ship it", (result as CompletionEvent.GoalComplete).objective)
    }

    @Test
    fun goalBlockedCarriesReason() {
        val result = classifier.classifyEvent("s1", event("goal/change", 9, buildJsonObject {
            put("kind", "goal/change")
            putJsonObject("goal") {
                put("id", "g1"); put("revision", 2); put("phase", "blocked")
                putJsonObject("blockedReason") { put("message", "no network") }
            }
        }))
        assertTrue(result is CompletionEvent.GoalBlocked)
        assertEquals("no network", (result as CompletionEvent.GoalBlocked).reason)
    }

    @Test
    fun approvalRequestedFires() {
        // The session is the frame's `agentId` and the correlation id is its `eventId`: 0.1.2
        // mints no separate approval id, so the event id is what an answer and a withdrawal both
        // name — and what the notification has to dedupe on.
        val request = buildJsonObject {
            put("toolName", "bash")
            put("reason", "justification")
        }
        val result = classifier.classifyWaterfall("approval/request", "evt-1", "s1", request)
        assertTrue(result is CompletionEvent.ReviewRequested)
        assertEquals("review:s1:evt-1", result!!.dedupKey)
    }

    @Test
    fun questionRequestedFires() {
        val request = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "which one?") })
            }
        }
        val result = classifier.classifyWaterfall("user-questions/request", "evt-2", "s1", request)
        assertTrue(result is CompletionEvent.QuestionRequested)
        assertEquals("which one?", (result as CompletionEvent.QuestionRequested).firstQuestion)
        assertEquals("evt-2", result.questionId)
        assertEquals("question:s1:evt-2", result.dedupKey)
    }

    @Test
    fun secondQuestionInSameSessionIsNotDeduped() {
        // The waterfall carries no seq — it is 0 for every request — so a seq-based key gave
        // every question in a session one shared value and the observer dropped the second and
        // later ones as duplicates. The key comes from the request's own eventId instead.
        val first = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "first?") })
            }
        }
        val second = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q2"); put("question", "second?") })
            }
        }
        val a = classifier.classifyWaterfall("user-questions/request", "evt-a", "s1", first)
        val b = classifier.classifyWaterfall("user-questions/request", "evt-b", "s1", second)
        assertTrue(a is CompletionEvent.QuestionRequested)
        assertTrue(b is CompletionEvent.QuestionRequested)
        assertEquals("question:s1:evt-a", a!!.dedupKey)
        assertEquals("question:s1:evt-b", b!!.dedupKey)
        assertTrue(a.dedupKey != b.dedupKey)
    }

    @Test
    fun replayedQuestionKeepsItsDedupKey() {
        // A reconnect re-delivers a still-pending waterfall with the same eventId, so the
        // observer sees the same key and stays silent rather than notifying the question twice.
        // The body is rebuilt differently on purpose: identity has to come from the eventId
        // alone, not from anything in the request.
        val first = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "again?") })
            }
        }
        val replayed = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject { put("id", "q1"); put("question", "again? (replayed)") })
            }
        }
        val a = classifier.classifyWaterfall("user-questions/request", "evt-4", "s1", first)
        val b = classifier.classifyWaterfall("user-questions/request", "evt-4", "s1", replayed)
        assertTrue(a is CompletionEvent.QuestionRequested)
        assertTrue(b is CompletionEvent.QuestionRequested)
        assertEquals("again?", (a as CompletionEvent.QuestionRequested).firstQuestion)
        assertEquals("again? (replayed)", (b as CompletionEvent.QuestionRequested).firstQuestion)
        assertEquals(a!!.dedupKey, b!!.dedupKey)
    }

    @Test
    fun anUnselectedWaterfallIsIgnored() {
        val result = classifier.classifyWaterfall("something/else", "evt-3", "s1", buildJsonObject { })
        assertNull(result)
    }

    @Test
    fun sessionIdleOnlyAfterRunning() {
        // Positional arguments now: the host forwards the Cordis listener's own argument list
        // rather than a named payload object.
        val stopped = listOf(JsonPrimitive("s1"), JsonPrimitive(false))
        val started = listOf(JsonPrimitive("s1"), JsonPrimitive(true))
        assertNull(classifier.classifyNotification("api-session/status", stopped))
        classifier.classifyNotification("api-session/status", started)
        assertTrue(
            classifier.classifyNotification("api-session/status", stopped) is CompletionEvent.SessionIdle,
        )
        // Second stop does not refire.
        assertNull(classifier.classifyNotification("api-session/status", stopped))
    }

    @Test
    fun consecutiveIdlesGetDistinctDedupKeys() {
        // The status notification carries no seq, so the classifier counts running→idle
        // transitions itself; a constant would let the observer's dedup silence every
        // completion after the first.
        val started = listOf(JsonPrimitive("s1"), JsonPrimitive(true))
        val stopped = listOf(JsonPrimitive("s1"), JsonPrimitive(false))

        classifier.classifyNotification("api-session/status", started)
        val first = classifier.classifyNotification("api-session/status", stopped)
        classifier.classifyNotification("api-session/status", started)
        val second = classifier.classifyNotification("api-session/status", stopped)

        assertTrue(first is CompletionEvent.SessionIdle)
        assertTrue(second is CompletionEvent.SessionIdle)
        assertTrue(first!!.dedupKey != second!!.dedupKey)
    }
}
