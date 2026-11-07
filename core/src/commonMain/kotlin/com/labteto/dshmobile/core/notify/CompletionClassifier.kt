package com.labteto.dshmobile.core.notify

import com.labteto.dshmobile.core.session.SessionEventEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Notification-worthy completions/requests derived from the wire. */
sealed interface CompletionEvent {
    val sessionId: String
    val seq: Long
    val dedupKey: String

    /** A turn ended with reason kind `completed` (code writing finished). */
    data class TurnComplete(override val sessionId: String, override val seq: Long, val turn: Int) : CompletionEvent {
        override val dedupKey: String get() = "turn:$sessionId:$seq"
    }

    /** The goal moved to phase `complete`. */
    data class GoalComplete(override val sessionId: String, override val seq: Long, val objective: String?) : CompletionEvent {
        override val dedupKey: String get() = "goal:$sessionId:$seq"
    }

    /** The goal moved to phase `blocked`. */
    data class GoalBlocked(override val sessionId: String, override val seq: Long, val reason: String?) : CompletionEvent {
        override val dedupKey: String get() = "goal:$sessionId:$seq"
    }

    /** A sandbox escalation or plan review waits for the user. */
    data class ReviewRequested(
        override val sessionId: String,
        override val seq: Long,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
    ) : CompletionEvent {
        override val dedupKey: String get() = "review:$sessionId:$approvalId"
    }

    /**
     * ask_user_question waits for the user.
     *
     * [questionId] is the pending request's `eventId`, the same correlation id [ReviewRequested]
     * keeps as `approvalId`. A waterfall carries no sequence number, so a key built from [seq]
     * gives every question in a session one shared value and the observer drops the second and
     * later ones as replays of the first. The event id is fresh per request and comes back
     * unchanged when a reconnect re-delivers the frame, which is what dedupe actually needs.
     */
    data class QuestionRequested(
        override val sessionId: String,
        override val seq: Long,
        val questionId: String,
        val firstQuestion: String?,
    ) : CompletionEvent {
        override val dedupKey: String get() = "question:$sessionId:$questionId"
    }

    /** A session that was running stopped (fallback completion signal). */
    data class SessionIdle(override val sessionId: String, override val seq: Long) : CompletionEvent {
        override val dedupKey: String get() = "idle:$sessionId:$seq"
    }
}

/**
 * Classifies session events and stream frames into [CompletionEvent]s.
 * Tracks per-session running state so `host/session-status(running:false)`
 * only fires once per run.
 */
class CompletionClassifier {
    private val running = mutableSetOf<String>()

    /**
     * Per-session count of running→idle transitions, used as [CompletionEvent.SessionIdle]'s
     * seq. The status notification carries no sequence of its own, and a constant would make
     * every completion after the first dedup against it downstream — only the first idle per
     * session would ever notify.
     */
    private val idleTransitions = mutableMapOf<String, Long>()

    fun classifyEvent(sessionId: String, event: SessionEventEnvelope): CompletionEvent? {
        val data = event.data as? JsonObject
        return when (event.type) {
            "turn/end" -> {
                val kind = data?.get("reason")?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
                if (kind == "completed") {
                    val turn = data["turn"]?.jsonPrimitive?.let { runCatching { it.content.toInt() }.getOrNull() } ?: 0
                    CompletionEvent.TurnComplete(sessionId, event.seq, turn)
                } else null
            }

            "goal/change" -> {
                val goal = data?.get("goal")?.jsonObject ?: return null
                when (goal["phase"]?.jsonPrimitive?.contentOrNull) {
                    "complete" -> CompletionEvent.GoalComplete(
                        sessionId,
                        event.seq,
                        goal["objective"]?.jsonPrimitive?.contentOrNull,
                    )
                    "blocked" -> CompletionEvent.GoalBlocked(
                        sessionId,
                        event.seq,
                        goal["blockedReason"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull,
                    )
                    else -> null
                }
            }

            else -> null
        }
    }

    /**
     * Classify one pending Remote Event waterfall.
     *
     * Replaces the `approval/requested` and `question/requested` mux frames. Two things moved with
     * them: the session is the frame's `agentId` rather than a `sessionId` inside the payload, and
     * the correlation id is the frame's `eventId` — 0.1.2 mints no separate `approvalId`, so the
     * event id is what an answer and a withdrawal both name.
     *
     * There is no sequence number on a waterfall. These are process-local requests with no place
     * in the durable log, so 0 is the honest value rather than a lookup this frame cannot satisfy.
     * Neither request dedupes on it: both keys use the event id, unique per request and replayed
     * unchanged after a reconnect (see [CompletionEvent.QuestionRequested.dedupKey]).
     *
     * @param event the Remote Event name.
     * @param eventId the pending request's correlation id, also what a later `cancel` names.
     * @param agentId the session the request is scoped to.
     * @param request the waterfall body, with `agent` and `signal` already stripped by the host.
     */
    fun classifyWaterfall(
        event: String,
        eventId: String,
        agentId: String,
        request: JsonObject,
    ): CompletionEvent? = when (event) {
        "approval/request" -> CompletionEvent.ReviewRequested(
            sessionId = agentId,
            seq = 0L,
            approvalId = eventId,
            toolName = request["toolName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            reason = request["reason"]?.jsonPrimitive?.contentOrNull,
        )

        "user-questions/request" -> CompletionEvent.QuestionRequested(
            sessionId = agentId,
            seq = 0L,
            questionId = eventId,
            firstQuestion = (request["questions"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()?.jsonObject?.get("question")?.jsonPrimitive?.contentOrNull,
        )

        else -> null
    }

    /**
     * Classify one ordinary Remote Event notification.
     *
     * Replaces `host/session-status`. The arguments are positional now — the host forwards the
     * Cordis listener's own argument list — so this reads `args[0]` and `args[1]` rather than
     * named payload keys.
     *
     * Note these are never replayed after a reconnect. A session that went idle while the phone
     * was disconnected produces no notification at all, which is why nothing here may be treated
     * as a durable record of what happened.
     */
    fun classifyNotification(event: String, args: List<JsonElement>): CompletionEvent? {
        if (event != "api-session/status") return null
        val sessionId = args.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: return null
        val isRunning = args.getOrNull(1)?.jsonPrimitive
            ?.let { runCatching { it.content.toBoolean() }.getOrNull() } ?: false
        val wasRunning = running.contains(sessionId)
        if (isRunning) {
            running.add(sessionId)
            return null
        }
        running.remove(sessionId)
        if (!wasRunning) return null
        val transition = (idleTransitions[sessionId] ?: 0L) + 1
        idleTransitions[sessionId] = transition
        return CompletionEvent.SessionIdle(sessionId, transition)
    }

    fun markSessionRunning(sessionId: String) {
        running.add(sessionId)
    }
}
